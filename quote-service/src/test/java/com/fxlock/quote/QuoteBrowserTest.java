package com.fxlock.quote;

import java.nio.file.*;
import java.time.Duration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.openqa.selenium.*;
import org.openqa.selenium.chrome.*;
import org.openqa.selenium.edge.*;
import org.openqa.selenium.support.ui.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import static org.assertj.core.api.Assertions.*;

@EnabledIfSystemProperty(named="runBrowser",matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"demo","test"})
@TestPropertySource(properties="spring.datasource.url=jdbc:h2:mem:m2browser;MODE=Oracle;DB_CLOSE_DELAY=-1")
class QuoteBrowserTest {
    @LocalServerPort int port;
    WebDriver driver;
    WebDriverWait wait;
    @BeforeEach void login() {
        String browser=System.getenv().getOrDefault("BROWSER","chrome");
        String binary=System.getenv("BROWSER_DRIVER_PATH");
        if ("edge".equalsIgnoreCase(browser)) {
            if(binary!=null) System.setProperty("webdriver.edge.driver",binary);
            var options=new EdgeOptions(); options.addArguments("--headless=new","--window-size=1365,1000");
            driver=new EdgeDriver(options);
        } else {
            if(binary!=null) System.setProperty("webdriver.chrome.driver",binary);
            var options=new ChromeOptions(); options.addArguments("--headless=new","--window-size=1365,1000");
            driver=new ChromeDriver(options);
        }
        wait=new WebDriverWait(driver,Duration.ofSeconds(15));
        driver.get("http://localhost:"+port);
        driver.findElement(By.name("username")).sendKeys("customer-demo");
        driver.findElement(By.name("password")).sendKeys("learn-fxlock");
        driver.findElement(By.name("password")).sendKeys(Keys.ENTER);
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("quote-form")));
        wait.until(ExpectedConditions.refreshed(d -> !new Select(d.findElement(By.id("source-wallet"))).getFirstSelectedOption().getDomProperty("value").isBlank()));
    }
    @AfterEach void quit() { if(driver!=null) driver.quit(); }
    void create() {
        var input=driver.findElement(By.id("amount")); input.clear(); input.sendKeys("1000.00");
        driver.findElement(By.id("create")).click();
        wait.until(ExpectedConditions.textToBe(By.id("target-amount"),"1167.66"));
    }
    @Test void quoteReviewAndCancellationWorkInTheBrowser() throws Exception {
        create(); assertThat(driver.findElement(By.id("offered-rate")).getText()).contains("1.1676600000");
        Path output=Path.of("target/browser-evidence"); Files.createDirectories(output);
        Files.write(output.resolve("quote-review.png"),((TakesScreenshot)driver).getScreenshotAs(OutputType.BYTES));
        driver.findElement(By.id("cancel")).click(); wait.until(ExpectedConditions.textToBe(By.id("quote-status"),"CANCELLED"));
        assertThat(driver.findElement(By.id("accept")).isEnabled()).isFalse();
    }
    @Test void unavailableSettlementNeverShowsFalseSuccess() {
        create(); driver.findElement(By.id("accept")).click();
        wait.until(ExpectedConditions.textToBePresentInElementLocated(By.id("message"),"M3 is not connected"));
        assertThat(driver.findElement(By.id("result")).isDisplayed()).isFalse();
    }
    @Test void pageReloadKeepsTheSameStoredOffer() {
        create(); String id=driver.findElement(By.id("quote-id")).getText();
        driver.navigate().refresh(); wait.until(ExpectedConditions.textToBe(By.id("quote-id"),id));
        assertThat(driver.findElement(By.id("target-amount")).getText()).isEqualTo("1167.66");
    }
    @Test void reverseDirectionUpdatesWalletsLabelsAndTheStoredQuoteReview() {
        new Select(driver.findElement(By.id("direction"))).selectByValue("USD-EUR");
        assertThat(driver.findElement(By.id("amount-label")).getText()).contains("USD");
        assertThat(new Select(driver.findElement(By.id("source-wallet"))).getFirstSelectedOption().getDomProperty("value")).isEqualTo("2");
        assertThat(new Select(driver.findElement(By.id("target-wallet"))).getFirstSelectedOption().getDomProperty("value")).isEqualTo("1");
        var amount=driver.findElement(By.id("amount")); amount.clear(); amount.sendKeys("100.00");
        driver.findElement(By.id("create")).click();
        wait.until(ExpectedConditions.textToBe(By.id("target-amount"),"85.30"));
        assertThat(driver.findElement(By.id("offered-rate")).getText()).contains("1 USD", "EUR");
        assertThat(driver.findElement(By.id("review-target-currency")).getText()).isEqualTo("EUR");
    }
}
