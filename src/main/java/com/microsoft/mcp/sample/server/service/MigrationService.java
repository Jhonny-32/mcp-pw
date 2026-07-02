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
        // Lists are migrated first, on the raw code: their child-element rule (scoped findElement)
        // and the get(0)/isEmpty usages must be resolved before the generic conversions run.
        String migrated = migrateListWebElements(code, notes);
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
        c = c.replaceAll("\\.getAttribute\\(", ".getAttribute(");

        // Explicit waits -> auto-waiting (flagged with TODO)
        if (c.contains("Thread.sleep")) {
            c = c.replaceAll("(?m)^(\\s*)Thread\\.sleep\\([^;]*\\);",
                    "$1" + TODO + " Thread.sleep removed; Playwright auto-waits. Use assertThat(locator).isVisible() if needed.");
            notes.add("Thread.sleep replaced with TODO (auto-waiting).");
        }
        if (c.contains("WebDriverWait") || c.contains("ExpectedConditions")) {
            c = c.replaceAll("(?m)^(\\s*).*(WebDriverWait|ExpectedConditions).*$",
                    "$1" + TODO + " Selenium explicit wait removed; use auto-waiting / assertThat(locator).");
            notes.add("WebDriverWait/ExpectedConditions flagged as TODO (auto-waiting).");
        }

        // Patterns without a direct equivalent
        if (c.contains("Actions")) {
            notes.add(TODO + " Actions usage detected: migrate to page.mouse / locator.hover()/dragTo().");
        }
        if (c.contains("JavascriptExecutor")) {
            notes.add(TODO + " JavascriptExecutor detected: migrate to page.evaluate(...).");
        }
        if (c.contains("switchTo")) {
            c = c.replaceAll("\\w+\\.switchTo\\(\\)\\.frame\\(",
                    TODO + " use page.frameLocator(...) -> frameLocator(");
            notes.add(TODO + " switchTo().frame migrated to frameLocator (review manually).");
        }
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

    /** Converts {@code @FindBy ... WebElement x;} fields into {@code Locator x = page.locator(...)} fields. */
    private String migrateFindBy(String code, List<String> notes) {
        // @FindBy(id = "x") private WebElement name;  -> Locator field
        Pattern p = Pattern.compile(
                "@FindBy\\(\\s*(id|css|name|className|xpath|linkText)\\s*=\\s*\"([^\"]+)\"\\s*\\)\\s*"
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
            String replacement = modifier + "Locator " + name + " = page.locator(\"" + selector + "\");";
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
            case "id" -> "#" + value;
            case "className" -> "." + value;
            case "name" -> "[name='" + value + "']";
            case "xpath" -> "xpath=" + value;
            case "linkText" -> "text=" + value;
            default -> value; // css
        };
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
                "@FindBy\\(\\s*(id|css|className|xpath)\\s*=\\s*\"([^\"]+)\"\\s*\\)\\s*"
                        + "(?:private|public|protected)?\\s*List<WebElement>\\s+(\\w+)\\s*;");
        Matcher m = field.matcher(code);
        StringBuilder sb = new StringBuilder();
        List<String> names = new ArrayList<>();
        while (m.find()) {
            String selector = toListSelector(m.group(1), m.group(2));
            String name = m.group(3);
            String accessor = "/** All elements matching \"" + selector + "\". */\n"
                    + "    public Locator " + name + "() {\n"
                    + "        return page.locator(\"" + selector + "\");\n"
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
        return switch (how) {
            case "id" -> "#" + value;
            case "className" -> "." + value;
            default -> value; // css, xpath -> as-is
        };
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
                {"switchTo()", "switchTo -> frameLocator/window"}}) {
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
