package com.fxlock.quote;

import java.nio.file.*;
import java.time.Duration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.openqa.selenium.*;
import org.openqa.selenium.chrome.*;
import org.openqa.selenium.support.ui.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import static org.assertj.core.api.Assertions.*;

@EnabledIfSystemProperty(named="runBrowser",matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"demo","test"})
@TestPropertySource(properties="spring.datasource.url=jdbc:h2:mem:historybrowser;MODE=Oracle;DB_CLOSE_DELAY=-1")
class TransactionHistoryBrowserTest {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    WebDriver driver;
    WebDriverWait wait;
    HistoryFixtures.Fixture mine,other;
    @BeforeEach void setup() {
        // This isolated H2 database is only used by this browser test class.
        jdbc.update("DELETE FROM TRANSACTION_HISTORY");
        jdbc.update("DELETE FROM WALLET_LEDGER");
        jdbc.update("DELETE FROM SETTLEMENT");
        jdbc.update("DELETE FROM FX_QUOTE");
        mine=HistoryFixtures.conversion(jdbc,"CUST001");
        other=HistoryFixtures.conversion(jdbc,"CUST002");
        String binary=System.getenv("BROWSER_DRIVER_PATH");
        if(binary!=null) System.setProperty("webdriver.chrome.driver",binary);
        var options=new ChromeOptions();options.addArguments("--headless=new","--window-size=1440,1100");
        driver=new ChromeDriver(options);
        wait=new WebDriverWait(driver,Duration.ofSeconds(15));
        driver.get(url("/login"));
        driver.findElement(By.name("username")).sendKeys("customer-demo");
        driver.findElement(By.name("password")).sendKeys("learn-fxlock",Keys.ENTER);
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("quote-form")));
    }
    @AfterEach void quit() { if(driver!=null) driver.quit(); }
    String url(String path) { return "http://localhost:"+port+path; }
    void loaded(int count) {
        wait.until(d -> !d.findElement(By.id("history-refresh")).getDomProperty("disabled").equals("true") &&
            d.findElements(By.cssSelector("#history-rows tr")).size()==count);
    }
    void screenshot(String name) throws Exception {
        Path output=Path.of("target/browser-evidence");Files.createDirectories(output);
        Files.write(output.resolve(name),((TakesScreenshot)driver).getScreenshotAs(OutputType.BYTES));
    }
    @Test void navigationDisplaysOnlyOwnTransactionsAndExactBalances() throws Exception {
        driver.findElement(By.cssSelector("nav a[href='/transactions']")).click();loaded(2);
        String text=driver.findElement(By.id("history-rows")).getText();
        assertThat(text).contains("Demo Customer One","1,167.66","1,000.00","4,000.00","2,167.66",mine.settlement())
            .doesNotContain(other.settlement());
        screenshot("transaction-history-test-data.png");
        new Select(driver.findElement(By.id("history-account"))).selectByValue(Long.toString(mine.source()));loaded(1);
        assertThat(driver.findElement(By.id("history-rows")).getText()).contains("EUR","4,000.00").doesNotContain("USD");
        driver.navigate().refresh();loaded(1);
        assertThat(new Select(driver.findElement(By.id("history-account"))).getFirstSelectedOption().getDomProperty("value"))
            .isEqualTo(Long.toString(mine.source()));
    }
    @Test void emptyAccountShowsHelpfulMessage() {
        jdbc.update("DELETE FROM TRANSACTION_HISTORY WHERE SETTLEMENT_ID=?",mine.settlement());
        driver.get(url("/transactions"));
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("history-empty")));
        assertThat(driver.findElement(By.id("history-empty")).getText()).contains("No transactions yet","does not move money");
        assertThat(driver.findElement(By.id("history-table-wrap")).isDisplayed()).isFalse();
    }
    @Test void foreignAccountUrlNeverShowsOtherCustomersData() {
        driver.get(url("/transactions?walletId="+other.source()));
        wait.until(ExpectedConditions.textToBePresentInElementLocated(By.id("history-message"),"not available for your login"));
        assertThat(driver.findElement(By.id("history-table-wrap")).isDisplayed()).isFalse();
        assertThat(driver.findElements(By.cssSelector("#history-rows tr"))).isEmpty();
    }
    @Test void mobileTableKeepsExactDecimalsAndEscapesCustomerNames() throws Exception {
        jdbc.update("UPDATE TRANSACTION_HISTORY SET CUSTOMER_NAME=?,UPDATED_BALANCE=123456789012345.1234 WHERE SETTLEMENT_ID=? AND CURRENCY_CODE='USD'",
            "<img src=x onerror=alert(1)>",mine.settlement());
        driver.manage().window().setSize(new Dimension(390,844));
        driver.get(url("/transactions?walletId="+mine.target()));loaded(1);
        assertThat(driver.findElement(By.id("history-rows")).getText()).contains("123,456,789,012,345.1234","<img src=x onerror=alert(1)>");
        assertThat(driver.findElements(By.cssSelector("#history-rows img"))).isEmpty();
        long overflow=((Number)((JavascriptExecutor)driver).executeScript("return document.documentElement.scrollWidth-window.innerWidth")).longValue();
        assertThat(overflow).isLessThanOrEqualTo(1);
        screenshot("transaction-history-mobile-test-data.png");
    }
}
