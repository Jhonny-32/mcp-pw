package com.microsoft.mcp.sample.server.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises MigrationService.convertPageObject against a synthetic Page Object that reproduces
 * the real-world patterns found in the AssociatedParty.java sample: @FindBy(how=,using=),
 * @FindAll, By field constants reused at call sites, isSelected(), switchTo().defaultContent()
 * and switchTo().frame(...), and custom GUIAutomationUtilities/UtilitiesIAP wrapper calls.
 */
class MigrationServiceTest {

    private final MigrationService service = new MigrationService();

    @Test
    void migratesHowUsingFindByAndFindAllAndByFieldConstants(@TempDir Path tempDir) throws IOException {
        String source = """
                package com.example.pages;

                import org.openqa.selenium.By;
                import org.openqa.selenium.WebElement;
                import org.openqa.selenium.support.FindAll;
                import org.openqa.selenium.support.FindBy;
                import org.openqa.selenium.support.How;
                import org.openqa.selenium.support.ui.ExpectedConditions;

                public class AssociatedParty extends PageObjectBase {

                    @FindBy(how = How.ID, using = "cat:businessProfileHeading")
                    private WebElement lbl_BusinessProfile;

                    @FindAll({@FindBy(how = How.ID, using = "cat:customerName"),
                            @FindBy(how = How.ID, using = "cat:businessName")})
                    private WebElement lbl_FullName;

                    private By by_btn_Cancel = By.id("btnCancel");
                    private By by_chk_Supplementary = By.xpath("//input[@id='chkSupp']");

                    public boolean accessAssociatedParty() {
                        try {
                            driver.switchTo().defaultContent();
                            UtilitiesIAP.expectingByCondition(driver, Duration.ofSeconds(20), Duration.ofSeconds(1),
                                    ExpectedConditions.numberOfElementsToBeMoreThan(By.xpath("//iframe"), 0));
                            driver.switchTo().frame(driver.findElement(by_frm_Search).findElements(by_frm_SearchSec).get(1));
                            driver.findElement(by_btn_Cancel).click();
                            GUIAutomationUtilities.scrollToObject(driver.findElement(by_btn_Cancel));
                            if (driver.findElement(by_chk_Supplementary).isSelected()) {
                                return true;
                            }
                            return false;
                        } catch (Exception | AssertionError e) {
                            return false;
                        }
                    }
                }
                """;

        Path file = tempDir.resolve("AssociatedParty.java");
        Files.writeString(file, source);

        String result = service.convertPageObject(file.toString());
        assertTrue(result.startsWith("[OK]"), "Expected a success summary, got: " + result);

        String migrated = Files.readString(file);
        System.out.println(migrated);

        // @FindBy(how=How.ID, using=...) -> Locator field with escaped ':' in the id selector
        assertTrue(migrated.contains("Locator lbl_BusinessProfile = page.locator(\"#cat\\\\:businessProfileHeading\");"),
                "how/using @FindBy was not converted to an escaped id Locator");

        // @FindAll -> single Locator field chained with .or(...)
        assertTrue(migrated.contains("Locator lbl_FullName = page.locator(\"#cat\\\\:customerName\").or(page.locator(\"#cat\\\\:businessName\"));"),
                "@FindAll was not converted to a Locator.or(...) chain");

        // By field constants -> String selector constants, usages keep compiling unchanged
        assertTrue(migrated.contains("String by_btn_Cancel = \"#btnCancel\";"),
                "By field constant was not converted to a String selector constant");
        assertTrue(migrated.contains("page.locator(by_btn_Cancel)"),
                "findElement(by_btn_Cancel) call site was not rewritten to page.locator(by_btn_Cancel)");

        // isSelected() -> isChecked()
        assertTrue(migrated.contains(".isChecked()"), "isSelected() was not mapped to isChecked()");
        assertFalse(migrated.contains(".isSelected()"), "isSelected() should not remain in migrated code");

        // switchTo().defaultContent() flagged, not left as a broken call
        assertTrue(migrated.contains("TODO MIGRATION") && migrated.contains("defaultContent()"),
                "switchTo().defaultContent() should be flagged with a TODO");

        // switchTo().frame(...) flagged with the ORIGINAL statement preserved (regression check for
        // the old bug where the TODO text was spliced mid-statement instead of replacing it cleanly)
        assertTrue(migrated.contains("Original: driver.switchTo().frame("),
                "switchTo().frame(...) TODO should preserve the original statement");
        assertFalse(migrated.matches("(?s).*//\\s*TODO MIGRATION:.*frameLocator\\(driver\\.findElement.*"),
                "switchTo().frame(...) TODO must not be spliced mid-expression");

        // Custom wrapper calls flagged without silently vanishing
        assertTrue(migrated.contains("UtilitiesIAP.expectingByCondition") && migrated.contains("TODO MIGRATION"),
                "custom explicit-wait wrapper call should be flagged, not silently deleted");
        assertTrue(migrated.contains("GUIAutomationUtilities.scrollToObject") && migrated.contains("TODO MIGRATION"),
                "GUIAutomationUtilities.scrollToObject should be flagged, not silently deleted");

        // Regression guard: the UtilitiesIAP.expectingByCondition(...) call above spans two source
        // lines. Commenting it out line-by-line (the old behavior) cut it mid-statement, leaving an
        // unclosed '(' -> unbalanced parens. Verify the whole file still balances.
        assertEquals(count(migrated, '('), count(migrated, ')'), "unbalanced parentheses after migration");
        assertEquals(count(migrated, '{'), count(migrated, '}'), "unbalanced braces after migration");
    }

    private static long count(String text, char ch) {
        return text.chars().filter(c -> c == ch).count();
    }
}
