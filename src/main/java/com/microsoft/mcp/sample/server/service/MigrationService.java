package com.microsoft.mcp.sample.server.service;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * MCP tools that automate migrating a Selenium WebDriver (Java) test project to
 * Playwright for Java.
 *
 * <p>Design based on the {@code CalculatorService} architecture: every operation is a
 * method annotated with {@link Tool} that returns a structured summary (text or JSON)
 * as a String.
 *
 * <p>Principles applied (see prompts/mcp-selenium-to-playwright.md):
 * <ul>
 *   <li>Read before writing.</li>
 *   <li>Idempotency: re-running does not duplicate imports nor corrupt the code.</li>
 *   <li>Report, don't assume: anything that cannot be migrated is flagged with
 *       {@code // TODO MIGRATION:}.</li>
 * </ul>
 */
@Service
public class MigrationService {

    /** Idempotency marker inserted into already-migrated files. */
    private static final String MIGRATED_MARKER = "// MIGRATED-TO-PLAYWRIGHT";
    private static final String TODO = "// TODO MIGRATION:";

    private final ObjectMapper json = new ObjectMapper();

    // ------------------------------------------------------------------
    // 4.1 scanSeleniumProject
    // ------------------------------------------------------------------
    @Tool(description = "Scans a Selenium (Java) test project and returns a migration inventory as JSON: " +
            "build tool, Selenium version, Page Objects, test layer, hooks/driver factory and hard patterns.")
    public String scanSeleniumProject(String projectPath) {
        Path root = Paths.get(projectPath);
        if (!Files.isDirectory(root)) {
            return error("Path is not a valid directory: " + projectPath);
        }

        ObjectNode out = json.createObjectNode();
        out.put("projectPath", slash(root.toAbsolutePath().toString()));

        // Build tool + Selenium version
        Path pom = root.resolve("pom.xml");
        Path gradle = root.resolve("build.gradle");
        String buildTool = "unknown";
        String buildFile = null;
        String seleniumVersion = "not detected";
        try {
            if (Files.exists(pom)) {
                buildTool = "maven";
                buildFile = pom.toString();
                seleniumVersion = firstGroup(read(pom),
                        "selenium-java</artifactId>\\s*<version>([^<]+)</version>", seleniumVersion);
            } else if (Files.exists(gradle)) {
                buildTool = "gradle";
                buildFile = gradle.toString();
                seleniumVersion = firstGroup(read(gradle),
                        "selenium-java:([0-9][^'\"]+)", seleniumVersion);
            }
        } catch (IOException e) {
            return error("Could not read the build file: " + e.getMessage());
        }
        out.put("buildTool", buildTool);
        if (buildFile != null) out.put("buildFile", slash(buildFile));
        out.put("seleniumVersion", seleniumVersion);

        // Walk .java sources
        ArrayNode pageObjects = json.createArrayNode();
        ArrayNode driverFactories = json.createArrayNode();
        ArrayNode hardPatterns = json.createArrayNode();
        boolean cucumber = false, junit = false, testng = false;
        int javaFiles = 0;

        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> javas = walk
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.toString().contains("\\build\\") && !p.toString().contains("/build/"))
                    .filter(p -> !p.toString().contains("\\target\\") && !p.toString().contains("/target/"))
                    .toList();
            for (Path p : javas) {
                javaFiles++;
                String code = read(p);
                String rel = slash(root.relativize(p).toString());

                if (code.contains("@FindBy") || code.contains("PageFactory")
                        || p.getFileName().toString().endsWith("Page.java")) {
                    pageObjects.add(rel);
                }
                if (code.contains("new ChromeDriver") || code.contains("new FirefoxDriver")
                        || code.contains("WebDriverManager") || code.contains("RemoteWebDriver")) {
                    driverFactories.add(rel);
                }
                if (code.contains("io.cucumber")) cucumber = true;
                if (code.contains("org.junit")) junit = true;
                if (code.contains("org.testng")) testng = true;

                addHardPatterns(hardPatterns, rel, code);
            }
        } catch (IOException e) {
            return error("Error walking the project: " + e.getMessage());
        }

        ArrayNode testLayer = json.createArrayNode();
        if (cucumber) testLayer.add("cucumber");
        if (testng) testLayer.add("testng");
        if (junit) testLayer.add("junit");

        out.put("javaFiles", javaFiles);
        out.set("pageObjects", pageObjects);
        out.set("driverFactories", driverFactories);
        out.set("testLayer", testLayer);
        out.set("hardPatterns", hardPatterns);
        out.put("nextStep", "convertPageObject(<each Page Object>)");

        return pretty(out);
    }

    // ------------------------------------------------------------------
    // 4.2 convertPageObject
    // ------------------------------------------------------------------
    @Tool(description = "Migrates a whole Page Object class to Playwright: @FindBy/PageFactory->Locator, " +
            "List<WebElement> fields->Locator accessor (get(0)->first(), isEmpty()->assertThat(...).isVisible(), " +
            "child findElement->scoped locator), dynamic locators (By->page.locator), Select dropdown->selectOption, " +
            "translates actions, removes explicit waits and manual scroll, and flags anything without a direct " +
            "equivalent with TODO MIGRATION.")
    public String convertPageObject(String filePath) {
        return convertSourceFile(filePath, "verifyCompilation(<projectPath>)");
    }

    // ------------------------------------------------------------------
    // 4.3 verifyCompilation
    // ------------------------------------------------------------------
    @Tool(description = "Compiles the project (mvn -q test-compile or gradle compileTestJava) and reports residual " +
            "errors, un-migrated Selenium imports and pending TODO MIGRATION. Does not declare the migration complete " +
            "while errors remain.")
    public String verifyCompilation(String projectPath) {
        Path root = Paths.get(projectPath);
        if (!Files.isDirectory(root)) return error("Path is not a directory: " + projectPath);

        boolean maven = Files.exists(root.resolve("pom.xml"));
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");

        List<String> cmd;
        if (maven) {
            cmd = List.of(windows ? "mvn.cmd" : "mvn", "-q", "test-compile");
        } else {
            cmd = List.of(windows ? "gradlew.bat" : "./gradlew", "compileTestJava");
        }

        StringBuilder report = new StringBuilder();
        int exit = -1;
        String compileOutput = "";
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd).directory(root.toFile()).redirectErrorStream(true);
            Process proc = pb.start();
            compileOutput = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (proc.waitFor(10, TimeUnit.MINUTES)) {
                exit = proc.exitValue();
            } else {
                proc.destroyForcibly();
                report.append("Compilation exceeded the time limit (10 min).\n");
            }
        } catch (IOException | InterruptedException e) {
            report.append(TODO).append(" could not run the compilation (").append(e.getMessage())
                    .append("). Run manually: ").append(String.join(" ", cmd)).append("\n");
        }

        // Scan for Selenium residue and pending TODOs
        int seleniumImports = 0, todos = 0, sleeps = 0;
        List<String> flagged = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.toString().contains("build") && !p.toString().contains("target")).toList()) {
                String code = read(p);
                String rel = slash(root.relativize(p).toString());
                if (code.contains("org.openqa.selenium")) { seleniumImports++; flagged.add("selenium import -> " + rel); }
                if (code.contains("Thread.sleep")) { sleeps++; flagged.add("Thread.sleep -> " + rel); }
                if (code.contains("TODO MIGRATION")) { todos++; flagged.add("pending TODO -> " + rel); }
            }
        } catch (IOException e) {
            report.append("Could not scan residue: ").append(e.getMessage()).append("\n");
        }

        boolean compiled = exit == 0;
        boolean clean = seleniumImports == 0 && sleeps == 0 && todos == 0;

        report.append("Command: ").append(String.join(" ", cmd)).append("\n");
        report.append("Exit code: ").append(exit).append(compiled ? "  (COMPILES)" : "  (DOES NOT COMPILE)").append("\n");
        report.append("Remaining Selenium imports: ").append(seleniumImports).append("\n");
        report.append("Remaining Thread.sleep: ").append(sleeps).append("\n");
        report.append("Pending TODO MIGRATION: ").append(todos).append("\n");
        if (!flagged.isEmpty()) {
            report.append("Pending items:\n");
            flagged.stream().limit(50).forEach(f -> report.append("  - ").append(f).append("\n"));
        }
        if (!compileOutput.isBlank() && !compiled) {
            String tail = compileOutput.length() > 2000 ? compileOutput.substring(compileOutput.length() - 2000) : compileOutput;
            report.append("--- compiler output (tail) ---\n").append(tail).append("\n");
        }

        report.append("\nResult: ");
        if (compiled && clean) {
            report.append("MIGRATION COMPLETE - compiles and no Selenium residue.");
        } else {
            report.append("MIGRATION INCOMPLETE - review the pending items before declaring it done.");
        }
        return report.toString();
    }

    // ==================================================================
    // Helpers
    // ==================================================================

    /** Shared migration of a source file (Page Object / test layer). */
    private String convertSourceFile(String filePath, String nextStep) {
        Path file = Paths.get(filePath);
        if (!Files.isRegularFile(file)) return error("File does not exist: " + filePath);

        String code;
        try {
            code = read(file);
        } catch (IOException e) {
            return error("Could not read: " + e.getMessage());
        }
        if (code.contains(MIGRATED_MARKER)) {
            return "Already migrated (marker present). Idempotent, no changes: " + filePath;
        }

        List<String> notes = new ArrayList<>();
        // By field/local constants are resolved first so that later steps (findElement/findElements
        // rewriting) see plain String selectors instead of By-typed variables.
        String migrated = migrateByFieldConstants(code, notes);
        // @FindAll combines several @FindBy strategies into one field; it must run before the
        // how/using normalization and before the single-@FindBy converters, since it consumes
        // the whole block itself.
        migrated = migrateFindAll(migrated, notes);
        // @FindBy(how = How.X, using = "...") is normalized to the shorthand form (id = "...", etc.)
        // so the existing shorthand converters below handle both styles uniformly.
        migrated = normalizeFindByHowUsing(migrated, notes);
        // Lists are migrated next, on the raw code: their child-element rule (scoped findElement)
        // and the get(0)/isEmpty usages must be resolved before the generic conversions run.
        migrated = migrateListWebElements(migrated, notes);
        migrated = translateSeleniumApis(migrated, notes);
        migrated = migrateFindBy(migrated, notes);
        migrated = prependMarker(migrated);

        try {
            write(file, migrated);
        } catch (IOException e) {
            return error("Could not write: " + e.getMessage());
        }
        return summary("convert", List.of(filePath), notes, nextStep);
    }

    /**
     * Translates Selenium action/location APIs to Playwright.
     * Anything that cannot be migrated automatically is flagged with {@code // TODO MIGRATION:}.
     */
    private String translateSeleniumApis(String code, List<String> notes) {
        String c = code;

        // Imports
        c = c.replaceAll("import\\s+org\\.openqa\\.selenium\\.support\\.[^;]+;\\s*\n", "");
        c = c.replaceAll("import\\s+org\\.openqa\\.selenium\\.[^;]+;\\s*\n", "");
        if (!c.contains("com.microsoft.playwright.Page")) {
            c = c.replaceFirst("(package[^;]+;\\s*\n)",
                    "$1\nimport com.microsoft.playwright.Page;\n"
                            + "import com.microsoft.playwright.Locator;\n"
                            + "import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;\n");
        }

        // By.xxx("v") -> selector string literal
        c = c.replaceAll("By\\.id\\(\"([^\"]+)\"\\)", "\"#$1\"");
        c = c.replaceAll("By\\.cssSelector\\(\"([^\"]+)\"\\)", "\"$1\"");
        c = c.replaceAll("By\\.className\\(\"([^\"]+)\"\\)", "\".$1\"");
        c = c.replaceAll("By\\.name\\(\"([^\"]+)\"\\)", "\"[name='$1']\"");
        c = c.replaceAll("By\\.tagName\\(\"([^\"]+)\"\\)", "\"$1\"");
        c = c.replaceAll("By\\.xpath\\(\"([^\"]+)\"\\)", "\"xpath=$1\"");
        c = c.replaceAll("By\\.linkText\\(\"([^\"]+)\"\\)", "\"text=$1\"");
        c = c.replaceAll("By\\.partialLinkText\\(\"([^\"]+)\"\\)", "\"text=$1\"");

        // Dynamic locators: methods returning By with a selector built at runtime
        // (e.g. return By.xpath(String.format(...))) -> method returning Locator with page.locator(...)
        c = migrateDynamicLocators(c, notes);

        // Location
        c = c.replaceAll("\\w+\\.findElements\\(", "page.locator(");
        c = c.replaceAll("\\w+\\.findElement\\(", "page.locator(");

        // Navigation
        c = c.replaceAll("\\w+\\.get\\(\"", "page.navigate(\"");
        c = c.replaceAll("\\w+\\.navigate\\(\\)\\.to\\(", "page.navigate(");

        // Element actions
        c = c.replaceAll("\\.sendKeys\\(", ".fill(");
        c = c.replaceAll("\\.getText\\(\\)", ".textContent()");
        c = c.replaceAll("\\.isDisplayed\\(\\)", ".isVisible()");
        c = c.replaceAll("\\.isSelected\\(\\)", ".isChecked()");
        c = c.replaceAll("\\.getAttribute\\(", ".getAttribute(");

        // Explicit waits -> auto-waiting (flagged with TODO, original statement preserved for review)
        if (c.contains("Thread.sleep")) {
            c = c.replaceAll("(?m)^(\\s*)Thread\\.sleep\\([^;]*\\);",
                    "$1" + TODO + " Thread.sleep removed; Playwright auto-waits. Use assertThat(locator).isVisible() if needed.");
            notes.add("Thread.sleep replaced with TODO (auto-waiting).");
        }
        if (c.contains("WebDriverWait") || c.contains("ExpectedConditions")) {
            c = flagStatementsContaining(c, notes, "(?:WebDriverWait|ExpectedConditions)",
                    "Selenium explicit wait removed; use auto-waiting / assertThat(locator).");
        }

        // Patterns without a direct equivalent
        if (c.contains("Actions")) {
            notes.add(TODO + " Actions usage detected: migrate to page.mouse / locator.hover()/dragTo().");
        }
        if (c.contains("JavascriptExecutor")) {
            notes.add(TODO + " JavascriptExecutor detected: migrate to page.evaluate(...).");
        }
        if (c.contains("defaultContent")) {
            c = c.replaceAll("(?m)^(\\s*)\\w+\\.switchTo\\(\\)\\.defaultContent\\(\\)\\s*;",
                    "$1" + TODO + " switchTo().defaultContent() removed; page/frameLocator() calls are already "
                            + "independent in Playwright, no implicit context switch is needed.");
            notes.add(TODO + " switchTo().defaultContent() call(s) removed (no Playwright equivalent needed).");
        }
        if (c.contains("switchTo()")) {
            c = flagStatementsContaining(c, notes, "\\w+\\.switchTo\\(\\)\\.frame\\(",
                    "switchTo().frame(...) has no direct Playwright equivalent; use page.frameLocator(<selector>) "
                            + "or a nested frameLocator(...) chain.");
        }
        // Custom automation-utility helpers with no direct Playwright equivalent: flagged for
        // manual review instead of guessed at, since their implementation is outside this project.
        c = flagUnmappedHelper(c, notes, "GUIAutomationUtilities.clickUntilDisappear",
                "custom retry-click helper; Playwright auto-waits, consider locator.click() (default timeout) "
                        + "or assertThat(locator).isHidden().");
        c = flagUnmappedHelper(c, notes, "GUIAutomationUtilities.clickUntilObjectAppear",
                "custom retry-click helper; consider locator.click() followed by "
                        + "assertThat(otherLocator).isVisible().");
        c = flagUnmappedHelper(c, notes, "GUIAutomationUtilities.scrollToObject",
                "manual scroll helper; Playwright auto-scrolls into view on interaction, this call can likely "
                        + "be deleted.");
        c = flagUnmappedHelper(c, notes, "GUIAutomationUtilities.switchToBrowserWindowByIndex",
                "window-switching helper; use BrowserContext.pages() / page.waitForPopup() in Playwright.");
        // <select> dropdown: org.openqa.selenium.support.ui.Select has no Playwright equivalent.
        // The <select> is already a Locator -> use Locator.selectOption(...) and web-first assertions.
        c = migrateSelectDropdown(c, notes);
        return c;
    }

    /**
     * Migrates the Selenium {@code <select>} dropdown ({@code Select} + {@code selectByXxx} /
     * {@code getFirstSelectedOption}) to Playwright ({@code Locator.selectOption(...)} and
     * {@code assertThat(locator.locator("option:checked"))}). The element is already located as a
     * Locator, so no new selectors are invented.
     */
    private String migrateSelectDropdown(String code, List<String> notes) {
        boolean hadSelect = code.contains("Select ") || code.contains("new Select(")
                || code.contains(".selectByVisibleText(") || code.contains(".selectByValue(")
                || code.contains(".selectByIndex(") || code.contains(".getFirstSelectedOption(")
                || code.contains(".getAllSelectedOptions(") || code.contains(".getOptions(");
        if (!hadSelect) return code;

        String c = code;

        // new Select(X) -> X  (the <select> is already a Locator)
        c = c.replaceAll("new\\s+Select\\(\\s*([^)]+?)\\s*\\)", "$1");

        // assertEquals(expected, recv.getFirstSelectedOption().getText()/textContent(), "msg"?) -> web-first
        Pattern assertPat = Pattern.compile(
                "assertEquals\\(\\s*([^,]+?)\\s*,\\s*(\\w+)\\.getFirstSelectedOption\\(\\)"
                        + "\\.(?:getText|textContent)\\(\\)\\s*(?:,\\s*\"[^\"]*\")?\\s*\\)\\s*;");
        Matcher am = assertPat.matcher(c);
        StringBuilder asb = new StringBuilder();
        int assertCount = 0;
        while (am.find()) {
            String expected = am.group(1).trim();
            String recv = am.group(2);
            am.appendReplacement(asb, Matcher.quoteReplacement(
                    "assertThat(" + recv + ".locator(\"option:checked\")).hasText(" + expected + ");"));
            assertCount++;
        }
        am.appendTail(asb);
        c = asb.toString();
        if (assertCount > 0) {
            notes.add("Converted " + assertCount + " assertEquals(getFirstSelectedOption) to "
                    + "assertThat(locator.locator(\"option:checked\")).hasText(...).");
        }

        // Selection methods (a single line per case; explicit form by default)
        c = c.replaceAll("\\.selectByVisibleText\\(\\s*([^;]+?)\\s*\\)", ".selectOption(new SelectOption().setLabel($1))");
        c = c.replaceAll("\\.selectByIndex\\(\\s*([^;]+?)\\s*\\)", ".selectOption(new SelectOption().setIndex($1))");
        c = c.replaceAll("\\.selectByValue\\(\\s*([^;]+?)\\s*\\)", ".selectOption($1)");

        // Remaining getFirstSelectedOption (outside assertEquals) -> option:checked
        c = c.replaceAll("(\\w+)\\.getFirstSelectedOption\\(\\)", "$1.locator(\"option:checked\")");

        // getOptions / getAllSelectedOptions -> approximation with TODO (List<WebElement> vs Locator differ)
        if (c.contains(".getOptions()") || c.contains(".getAllSelectedOptions()")) {
            c = c.replaceAll("(\\w+)\\.getAllSelectedOptions\\(\\)", "$1.locator(\"option:checked\")");
            c = c.replaceAll("(\\w+)\\.getOptions\\(\\)", "$1.locator(\"option\")");
            notes.add(TODO + " getOptions()/getAllSelectedOptions() migrated to locator(\"option\"...); "
                    + "review the usage: in Playwright it is a Locator, not a List<WebElement> (use .all()/.count()).");
        }

        // Variable type: Select x = ... -> Locator x = ...  (keeps the alias, it is already a Locator)
        c = c.replaceAll("\\bSelect(\\s+\\w+\\s*=)", "Locator$1");

        // Import SelectOption only if it is used
        if (c.contains("new SelectOption()") && !c.contains("com.microsoft.playwright.options.SelectOption")) {
            c = addImportAfterPackage(c, "import com.microsoft.playwright.options.SelectOption;\n");
        }

        notes.add("Selenium <select> dropdown (Select) migrated to Playwright Locator.selectOption(...).");
        return c;
    }

    /**
     * Normalizes {@code @FindBy(how = How.ID, using = "value")} to the shorthand form
     * {@code @FindBy(id = "value")} so the rest of the pipeline (single field, list field) only has
     * to understand one annotation shape.
     */
    private String normalizeFindByHowUsing(String code, List<String> notes) {
        Pattern p = Pattern.compile(
                "@FindBy\\(\\s*how\\s*=\\s*How\\.(ID|CLASS_NAME|CSS|NAME|XPATH|LINK_TEXT|PARTIAL_LINK_TEXT|"
                        + "TAG_NAME|ID_OR_NAME)\\s*,\\s*using\\s*=\\s*\"([^\"]+)\"\\s*\\)");
        Matcher m = p.matcher(code);
        StringBuilder sb = new StringBuilder();
        int count = 0;
        while (m.find()) {
            String how = m.group(1);
            String value = m.group(2);
            if ("ID_OR_NAME".equals(how)) {
                notes.add(TODO + " @FindBy(how = How.ID_OR_NAME, using = \"" + value + "\") mapped to an id-only "
                        + "selector; verify manually if the element is actually located by its name attribute.");
            }
            String replacement = "@FindBy(" + howEnumToShorthandKey(how) + " = \"" + value + "\")";
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
            count++;
        }
        m.appendTail(sb);
        if (count > 0) {
            notes.add("Normalized " + count + " @FindBy(how = How.X, using = \"...\") annotations to shorthand form.");
        }
        return sb.toString();
    }

    private String howEnumToShorthandKey(String how) {
        return switch (how) {
            case "ID", "ID_OR_NAME" -> "id";
            case "CLASS_NAME" -> "className";
            case "CSS" -> "css";
            case "NAME" -> "name";
            case "XPATH" -> "xpath";
            case "LINK_TEXT", "PARTIAL_LINK_TEXT" -> "linkText";
            case "TAG_NAME" -> "tagName";
            default -> "css";
        };
    }

    /**
     * Migrates {@code @FindAll({@FindBy(...), @FindBy(...)})} fields (match-any-of-these-locators)
     * into a single {@code Locator} field chained with {@code .or(...)}, which is Playwright's
     * equivalent of "the first strategy that matches". Runs before {@link #normalizeFindByHowUsing}
     * since it consumes the whole block itself; understands both the shorthand and how/using
     * @FindBy forms inside the block.
     */
    private String migrateFindAll(String code, List<String> notes) {
        Pattern block = Pattern.compile(
                "@FindAll\\(\\s*\\{(.*?)\\}\\s*\\)\\s*((?:private|public|protected)\\s+)?WebElement\\s+(\\w+)\\s*;",
                Pattern.DOTALL);
        Pattern howUsing = Pattern.compile(
                "@FindBy\\(\\s*how\\s*=\\s*How\\.(\\w+)\\s*,\\s*using\\s*=\\s*\"([^\"]+)\"\\s*\\)");
        Pattern shorthand = Pattern.compile(
                "@FindBy\\(\\s*(id|css|name|className|xpath|linkText|tagName)\\s*=\\s*\"([^\"]+)\"\\s*\\)");

        Matcher m = block.matcher(code);
        StringBuilder sb = new StringBuilder();
        int fieldCount = 0;
        while (m.find()) {
            String inner = m.group(1);
            String modifier = m.group(2) == null ? "" : m.group(2);
            String name = m.group(3);

            List<String> selectors = new ArrayList<>();
            Matcher hu = howUsing.matcher(inner);
            while (hu.find()) selectors.add(toSelector(howEnumToShorthandKey(hu.group(1)), hu.group(2)));
            Matcher sh = shorthand.matcher(inner);
            while (sh.find()) selectors.add(toSelector(sh.group(1), sh.group(2)));

            if (selectors.isEmpty()) {
                // Unrecognized @FindBy shape inside @FindAll: leave untouched and flag for manual review.
                notes.add(TODO + " @FindAll for field '" + name + "' could not be parsed automatically; "
                        + "convert manually to page.locator(...).or(...).");
                continue;
            }
            StringBuilder expr = new StringBuilder("page.locator(\"" + javaStringLiteral(selectors.get(0)) + "\")");
            for (int i = 1; i < selectors.size(); i++) {
                expr.append(".or(page.locator(\"").append(javaStringLiteral(selectors.get(i))).append("\"))");
            }
            String replacement = modifier + "Locator " + name + " = " + expr + ";";
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
            fieldCount++;
        }
        m.appendTail(sb);
        if (fieldCount > 0) {
            notes.add("Migrated " + fieldCount + " @FindAll field(s) to Locator fields chained with .or(...).");
            notes.add(TODO + " add a 'private final Page page;' field initialized by the constructor for the "
                    + "@FindAll-derived Locator field(s) above.");
        }
        return sb.toString();
    }

    /**
     * Converts {@code By} field/local constants (e.g. {@code private By by_btn_Cancel = By.id("x");}) to
     * plain {@code String} selector constants with the same name. This lets the generic
     * {@code findElement}/{@code findElements} rewriting in {@link #translateSeleniumApis} keep working
     * unchanged at call sites such as {@code driver.findElement(by_btn_Cancel)}, since the variable is
     * still a valid single argument, just String-typed instead of By-typed.
     */
    private String migrateByFieldConstants(String code, List<String> notes) {
        Pattern p = Pattern.compile(
                "((?:(?:private|public|protected|static|final)\\s+)*)By\\s+(\\w+)\\s*=\\s*By\\."
                        + "(id|cssSelector|className|name|tagName|xpath|linkText|partialLinkText)\\(\\s*\"([^\"]+)\"\\s*\\)\\s*;");
        Matcher m = p.matcher(code);
        StringBuilder sb = new StringBuilder();
        int count = 0;
        while (m.find()) {
            String modifiers = m.group(1);
            String name = m.group(2);
            String strategy = shorthandStrategyKey(m.group(3));
            String value = m.group(4);
            String selector = toSelector(strategy, value);
            String replacement = modifiers + "String " + name + " = \"" + javaStringLiteral(selector) + "\";";
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
            count++;
        }
        m.appendTail(sb);
        if (count > 0) {
            notes.add("Converted " + count + " By field constant(s) to String selector constants "
                    + "(usages via driver.findElement(name)/findElements(name) keep compiling).");
        }
        return sb.toString();
    }

    /** Maps the By.xxx() factory method name to the shorthand strategy key used by {@link #toSelector}. */
    private String shorthandStrategyKey(String byFactoryMethod) {
        return switch (byFactoryMethod) {
            case "cssSelector" -> "css";
            case "partialLinkText" -> "linkText";
            default -> byFactoryMethod; // id, className, name, tagName, xpath, linkText already match
        };
    }

    /**
     * Replaces whole Java statements containing {@code keywordPattern} with a single-line TODO
     * comment that preserves the original statement text (whitespace-collapsed), instead of
     * discarding it. Operates on the full statement span (from the previous {@code ; { }} up to the
     * next {@code ;}) rather than per physical line, so long calls that wrap across several lines
     * (very common for the explicit-wait wrappers in this codebase) are not cut mid-statement, which
     * would otherwise leave an unclosed '(' and break compilation.
     */
    private String flagStatementsContaining(String code, List<String> notes, String keywordPattern, String hint) {
        Matcher km = Pattern.compile(keywordPattern).matcher(code);
        List<int[]> spans = new ArrayList<>(); // {indentStart, contentStart, statementEnd}
        int searchFrom = 0;
        while (searchFrom <= code.length() && km.find(searchFrom)) {
            int idx = km.start();
            int boundary = lastStatementBoundary(code, idx);
            int indentStart = boundary + 1;
            while (indentStart < code.length() && (code.charAt(indentStart) == '\n' || code.charAt(indentStart) == '\r')) {
                indentStart++;
            }
            int contentStart = indentStart;
            while (contentStart < code.length() && (code.charAt(contentStart) == ' ' || code.charAt(contentStart) == '\t')) {
                contentStart++;
            }
            int semi = code.indexOf(';', idx);
            int stmtEnd = semi < 0 ? code.length() : semi + 1;
            if (stmtEnd <= contentStart) { searchFrom = idx + 1; continue; }
            spans.add(new int[]{indentStart, contentStart, stmtEnd});
            searchFrom = stmtEnd;
        }
        if (spans.isEmpty()) return code;

        StringBuilder sb = new StringBuilder();
        int last = 0;
        for (int[] span : spans) {
            sb.append(code, last, span[0]);
            String indent = code.substring(span[0], span[1]);
            String original = code.substring(span[1], span[2]).trim().replaceAll("\\s+", " ");
            sb.append(indent).append(TODO).append(' ').append(hint).append(" Original: ").append(original);
            last = span[2];
        }
        sb.append(code, last, code.length());
        notes.add(TODO + " " + spans.size() + " statement(s) flagged: " + hint);
        return sb.toString();
    }

    /** Index of the last {@code ; { }} strictly before {@code beforeIndex}, or -1 if none. */
    private int lastStatementBoundary(String code, int beforeIndex) {
        for (int i = beforeIndex - 1; i >= 0; i--) {
            char c = code.charAt(i);
            if (c == ';' || c == '{' || c == '}') return i;
        }
        return -1;
    }

    /**
     * Flags statements calling a custom automation-utility helper ({@code needle}) that has no direct
     * Playwright equivalent, by inserting a TODO comment line above the statement. The original
     * statement is left untouched (unlike {@link #flagStatementsContaining}) because these helpers
     * live outside this project and a safe rewrite cannot be guessed.
     */
    private String flagUnmappedHelper(String code, List<String> notes, String needle, String hint) {
        if (!code.contains(needle)) return code;
        Pattern p = Pattern.compile("(?m)^(\\s*)(.*" + Pattern.quote(needle) + ".*)$");
        Matcher m = p.matcher(code);
        StringBuilder sb = new StringBuilder();
        int count = 0;
        while (m.find()) {
            String indent = m.group(1);
            String replacement = indent + TODO + " " + hint + "\n" + m.group(0);
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
            count++;
        }
        m.appendTail(sb);
        if (count > 0) {
            notes.add(TODO + " " + count + " call(s) to " + needle + " flagged (" + hint + ").");
        }
        return sb.toString();
    }

    /** Converts {@code @FindBy ... WebElement x;} fields into {@code Locator x = page.locator(...)} fields. */
    private String migrateFindBy(String code, List<String> notes) {
        // @FindBy(id = "x") private WebElement name;  -> Locator field
        Pattern p = Pattern.compile(
                "@FindBy\\(\\s*(id|css|name|className|xpath|linkText|tagName)\\s*=\\s*\"([^\"]+)\"\\s*\\)\\s*"
                        + "((?:private|public|protected)\\s+)?WebElement\\s+(\\w+)\\s*;");
        Matcher m = p.matcher(code);
        StringBuilder sb = new StringBuilder();
        int count = 0;
        while (m.find()) {
            String how = m.group(1);
            String value = m.group(2);
            String modifier = m.group(3) == null ? "" : m.group(3);
            String name = m.group(4);
            String selector = toSelector(how, value);
            String replacement = modifier + "Locator " + name + " = page.locator(\"" + javaStringLiteral(selector) + "\");";
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
            count++;
        }
        m.appendTail(sb);
        String result = sb.toString();
        result = result.replaceAll("(?m)^\\s*PageFactory\\.initElements\\([^;]*\\);\\s*\n", "");
        if (count > 0) notes.add("Migrated " + count + " @FindBy fields to Locator fields.");
        // Inject a Page field if none exists
        if (result.contains("page.locator") && !result.contains("Page page")) {
            notes.add(TODO + " add a 'private final Page page;' field initialized by the constructor in this Page Object.");
        }
        // Initialization-order warning: Locator fields with an initializer depend on 'page' already being assigned.
        if (count > 0 && result.contains("page.locator")) {
            notes.add(TODO + " 'Locator x = page.locator(...)' fields require 'page' to be assigned before they are "
                    + "initialized; if you assign 'page' in the constructor body, initialize the locators inside the "
                    + "constructor too (after page) to avoid a NullPointerException.");
        }
        return result;
    }

    private String toSelector(String how, String value) {
        return switch (how) {
            case "id" -> "#" + escapeCssToken(value);
            case "className" -> "." + escapeCssToken(value);
            case "name" -> "[name='" + value.replace("'", "\\'") + "']";
            case "xpath" -> "xpath=" + value;
            case "linkText" -> "text=" + value;
            case "idOrName" -> "#" + escapeCssToken(value) + ", [name='" + value.replace("'", "\\'") + "']";
            default -> value; // css, tagName
        };
    }

    /**
     * Escapes CSS special characters in an id/class token so it stays a valid selector.
     * Selenium accepted these raw (e.g. {@code "cat:businessProfileHeading"}); Playwright's
     * {@code page.locator(...)} parses them as CSS, where an un-escaped ':' or '.' changes meaning.
     */
    private String escapeCssToken(String raw) {
        StringBuilder sb = new StringBuilder();
        for (char ch : raw.toCharArray()) {
            if (":.#[](),/ +~>*=\"'&|^$".indexOf(ch) >= 0) sb.append('\\');
            sb.append(ch);
        }
        return sb.toString();
    }

    /**
     * Escapes a selector value (which may now contain the backslashes {@link #escapeCssToken} added)
     * so it stays a valid Java string literal when written into the generated source file. Without
     * this, a CSS escape like {@code \:} becomes an illegal Java escape sequence and the migrated
     * file fails to compile.
     */
    private String javaStringLiteral(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * Migrates dynamic locator methods (those that take parameters and build the selector at runtime)
     * from Selenium to Playwright. Understands any location strategy:
     * <pre>
     *   public By lbl_title(String t) { return By.xpath(String.format("//div[@class='%s']//h2", t)); }
     *      -&gt;
     *   public Locator lbl_title(String t) { return page.locator(String.format("//div[@class='%s']//h2", t)); }
     * </pre>
     * For id/className/name/linkText the selector prefix is preserved via concatenation
     * ("#" + expr, "." + expr, ...); xpath/css/tagName pass the expression through unchanged
     * (Playwright auto-detects XPath when it starts with "//").
     */
    private String migrateDynamicLocators(String code, List<String> notes) {
        // 1) Method signature: 'public/private/protected By name(' -> '... Locator name('
        String c = code.replaceAll("(\\b(?:public|private|protected)\\s+)By(\\s+\\w+\\s*\\()", "$1Locator$2");

        // 2) 'return By.<strategy>(<expression>);' -> 'return page.locator(<selector>);'
        Pattern p = Pattern.compile(
                "return\\s+By\\.(id|cssSelector|className|name|tagName|xpath|linkText|partialLinkText)\\((.*)\\)\\s*;");
        Matcher m = p.matcher(c);
        StringBuilder sb = new StringBuilder();
        int count = 0;
        while (m.find()) {
            String how = m.group(1);
            String arg = m.group(2).trim();
            String selectorExpr = toDynamicSelectorExpr(how, arg);
            m.appendReplacement(sb, Matcher.quoteReplacement("return page.locator(" + selectorExpr + ");"));
            count++;
        }
        m.appendTail(sb);
        if (count > 0) notes.add("Migrated " + count + " dynamic locators (By -> Locator with page.locator).");
        return sb.toString();
    }

    /** Builds the selector expression for a dynamic locator, preserving the strategy prefix. */
    private String toDynamicSelectorExpr(String how, String arg) {
        // Note: id/className prefixes are concatenated as-is; if the runtime value can contain CSS
        // special characters (':', '.', etc.) it must be escaped by the caller, same as escapeCssToken.
        return switch (how) {
            case "id" -> "\"#\" + " + arg;
            case "className" -> "\".\" + " + arg;
            case "name" -> "\"[name='\" + " + arg + " + \"']\"";
            case "linkText", "partialLinkText" -> "\"text=\" + " + arg;
            default -> arg; // xpath, cssSelector, tagName -> expression as-is
        };
    }

    /**
     * Migrates ONLY {@code @FindBy ... List<WebElement>} fields and their usage. Leaves the rest of the
     * class untouched (a mixed Selenium/Playwright migration is allowed). If there are no lists to
     * migrate, returns the code unchanged (idempotent).
     */
    private String migrateListWebElements(String code, List<String> notes) {
        // 1) Eager field @FindBy ... List<WebElement> name; -> lazy accessor public Locator name()
        Pattern field = Pattern.compile(
                "@FindBy\\(\\s*(id|css|className|xpath|name|linkText|tagName)\\s*=\\s*\"([^\"]+)\"\\s*\\)\\s*"
                        + "(?:private|public|protected)?\\s*List<WebElement>\\s+(\\w+)\\s*;");
        Matcher m = field.matcher(code);
        StringBuilder sb = new StringBuilder();
        List<String> names = new ArrayList<>();
        while (m.find()) {
            String selector = toListSelector(m.group(1), m.group(2));
            String name = m.group(3);
            String accessor = "/** All elements matching \"" + selector + "\". */\n"
                    + "    public Locator " + name + "() {\n"
                    + "        return page.locator(\"" + javaStringLiteral(selector) + "\");\n"
                    + "    }";
            m.appendReplacement(sb, Matcher.quoteReplacement(accessor));
            names.add(name);
        }
        m.appendTail(sb);
        if (names.isEmpty()) return code; // nothing list-related -> no changes

        String c = sb.toString();
        notes.add("Migrated " + names.size() + " List<WebElement> @FindBy fields to Locator accessors: " + names + ".");

        // 4) Child element: VAR.findElement(By.xxx("sel")) -> VAR.locator("<sel>") (scoped, no findElement)
        c = c.replaceAll("(\\w+)\\.findElement\\(By\\.cssSelector\\(\"([^\"]+)\"\\)\\)", "$1.locator(\"$2\")");
        c = c.replaceAll("(\\w+)\\.findElement\\(By\\.id\\(\"([^\"]+)\"\\)\\)", "$1.locator(\"#$2\")");
        c = c.replaceAll("(\\w+)\\.findElement\\(By\\.className\\(\"([^\"]+)\"\\)\\)", "$1.locator(\".$2\")");
        c = c.replaceAll("(\\w+)\\.findElement\\(By\\.xpath\\(\"([^\"]+)\"\\)\\)", "$1.locator(\"$2\")");

        for (String name : names) {
            String q = Pattern.quote(name);
            // 2) get(0) -> first(), get(n) -> nth(n)
            c = c.replaceAll("\\b" + q + "\\.get\\(\\s*0\\s*\\)", name + "().first()");
            c = c.replaceAll("\\b" + q + "\\.get\\(\\s*([^)]+?)\\s*\\)", name + "().nth($1)");
            // 3) assertFalse/assertTrue(list.isEmpty(), "msg") -> web-first assertion on the first element
            c = c.replaceAll("assert(?:False|True)\\(\\s*!?\\s*" + q + "\\.isEmpty\\(\\)\\s*,\\s*\"[^\"]*\"\\s*\\)\\s*;",
                    "assertThat(" + name + "().first()).isVisible();");
            // Warning: uses not covered (iteration / size) require manual review
            if (c.matches("(?s).*:\\s*" + q + "\\b.*") || c.contains(name + ".size()") || c.contains(name + ".stream()")) {
                notes.add(TODO + " '" + name + "' is used in iteration/size/stream; review manually "
                        + "(use " + name + "().all() or " + name + "().count()).");
            }
        }
        notes.add("Replaced get(0)->first(), get(n)->nth(n) and isEmpty() checks->assertThat(...).isVisible().");

        // 6) Local variable WebElement -> Locator (only when the assignment already yields a Locator)
        c = c.replaceAll("\\bWebElement(\\s+\\w+\\s*=\\s*[^;]*?(?:\\.first\\(\\)|\\.nth\\(|\\.locator\\())",
                "Locator$1");

        // 5) Manual scroll: remove scrollToElement(...); lines (Playwright auto-scrolls)
        if (c.contains("scrollToElement")) {
            c = c.replaceAll("(?m)^\\s*scrollToElement\\([^;]*\\);\\s*\n", "");
            notes.add("Removed scrollToElement calls (Playwright auto-scrolls on interaction).");
        }

        // Required imports
        if (!c.contains("com.microsoft.playwright.Locator")) {
            c = addImportAfterPackage(c, "import com.microsoft.playwright.Locator;\n");
        }
        if (!c.contains("com.microsoft.playwright.Page")) {
            c = addImportAfterPackage(c, "import com.microsoft.playwright.Page;\n");
        }
        if (c.contains("assertThat(") && !c.contains("PlaywrightAssertions.assertThat")) {
            c = addImportAfterPackage(c,
                    "import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;\n");
        }
        if (c.contains("page.locator") && !c.contains("Page page")) {
            notes.add(TODO + " add a 'private final Page page;' field initialized by the constructor in this Page Object.");
        }

        // Imports left unused after migrating ONLY the lists (kept if other code still uses them)
        c = removeImportIfUnused(c, "java.util.List", "List<");
        c = removeImportIfUnused(c, "org.openqa.selenium.By", "By.");
        return c;
    }

    /** Selector conversion for list fields (xpath/css pass through as-is; page.locator accepts them). */
    private String toListSelector(String how, String value) {
        return toSelector(how, value);
    }

    private String addImportAfterPackage(String code, String importLine) {
        if (Pattern.compile("package[^;]+;").matcher(code).find()) {
            return code.replaceFirst("(package[^;]+;\\s*\n)", "$1" + Matcher.quoteReplacement(importLine));
        }
        return importLine + code;
    }

    /** Removes the given import only if {@code usageToken} no longer appears in the rest of the code. */
    private String removeImportIfUnused(String code, String importFqcn, String usageToken) {
        String withoutImport = code.replaceAll(
                "(?m)^\\s*import\\s+(?:static\\s+)?" + Pattern.quote(importFqcn) + "\\s*;\\s*\n", "");
        return withoutImport.contains(usageToken) ? code : withoutImport;
    }

    private void addHardPatterns(ArrayNode arr, String rel, String code) {
        for (String[] pat : new String[][]{
                {"Actions", "Actions API (hover/drag/keyboard)"},
                {"JavascriptExecutor", "JavascriptExecutor -> page.evaluate"},
                {"WebDriverWait", "explicit wait -> auto-waiting"},
                {"ExpectedConditions", "explicit wait -> auto-waiting"},
                {"Thread.sleep", "fixed sleep -> remove"},
                {"switchTo()", "switchTo -> frameLocator/window"},
                {"@FindAll(", "combined locator strategy -> Locator.or(...)"},
                {"how = How.", "@FindBy(how=,using=) style -> normalized to shorthand"},
                {".isSelected()", "checkbox/radio state -> Locator.isChecked()"},
                {"GUIAutomationUtilities.", "custom automation helper -> needs manual review"},
                {"UtilitiesIAP.expectingByCondition", "custom explicit-wait wrapper -> needs manual review"}}) {
            if (code.contains(pat[0])) {
                ObjectNode n = json.createObjectNode();
                n.put("file", rel);
                n.put("pattern", pat[0]);
                n.put("hint", pat[1]);
                arr.add(n);
            }
        }
    }

    // -- IO / formatting ----------------------------------------------

    private String read(Path p) throws IOException {
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    private void write(Path p, String content) throws IOException {
        Files.writeString(p, content, StandardCharsets.UTF_8);
    }

    private String prependMarker(String code) {
        return MIGRATED_MARKER + "\n" + code;
    }

    private String firstGroup(String text, String regex, String fallback) {
        Matcher m = Pattern.compile(regex).matcher(text);
        return m.find() ? m.group(1) : fallback;
    }

    private String summary(String tool, List<String> files, List<String> notes, String nextStep) {
        StringBuilder sb = new StringBuilder();
        sb.append("[OK] ").append(tool).append(" applied.\n");
        sb.append("Modified files:\n");
        files.forEach(f -> sb.append("  - ").append(f).append("\n"));
        sb.append("Warnings / TODOs:\n");
        if (notes.isEmpty()) sb.append("  (none)\n");
        else notes.forEach(n -> sb.append("  - ").append(n).append("\n"));
        sb.append("Next step: ").append(nextStep);
        return sb.toString();
    }

    private String error(String msg) {
        return "ERROR: " + msg;
    }

    private String pretty(ObjectNode node) {
        try {
            DefaultIndenter indenter = new DefaultIndenter("  ", "\n"); // 2 spaces, Unix newline
            DefaultPrettyPrinter printer = new DefaultPrettyPrinter()
                    .withObjectIndenter(indenter)
                    .withArrayIndenter(indenter);
            return json.writer(printer).writeValueAsString(node);
        } catch (Exception e) {
            return node.toString();
        }
    }

    /** Normalizes path separators to '/' to avoid escaping '\\' in JSON and to stay portable. */
    private String slash(String path) {
        return path == null ? null : path.replace('\\', '/');
    }
}
