package com.fxlock.quote;
import java.nio.file.*;
import java.time.Duration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.openqa.selenium.*;
import org.openqa.selenium.chrome.*;
import org.openqa.selenium.support.ui.*;
import static org.assertj.core.api.Assertions.*;
/** Uses the separately running Vite frontend and disposable demo backend, never Oracle. */
@EnabledIfSystemProperty(named="runReact",matches="true")
class ReactFrontendBrowserTest {
 WebDriver driver;WebDriverWait wait;
 String base="http://localhost:5173";
 @BeforeEach void start(){var options=new ChromeOptions();options.addArguments("--headless=new","--window-size=1440,1100");driver=new ChromeDriver(options);wait=new WebDriverWait(driver,Duration.ofSeconds(15));}
 @AfterEach void stop(TestInfo info)throws Exception{if(driver!=null){try{screenshot("last-"+info.getTestMethod().orElseThrow().getName()+".png");System.out.println("Browser finished at "+driver.getCurrentUrl());System.out.println("Visible alerts: "+driver.findElements(By.cssSelector("[role=alert]")).stream().map(WebElement::getText).toList());}finally{driver.quit();}}}
 void login(){driver.get(base+"/login");driver.findElement(By.id("username")).sendKeys("customer-demo");driver.findElement(By.id("password")).sendKeys("learn-fxlock");driver.findElement(By.cssSelector("button[type=submit]")).click();wait.until(d->d.getCurrentUrl().endsWith("/dashboard"));}
 void create(){driver.get(base+"/exchange");wait.until(d->!d.findElement(By.id("sourceWallet")).getDomProperty("value").isBlank());driver.findElement(By.cssSelector("button[type=submit]")).click();wait.until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector(".quote-receive")));}
 void screenshot(String name)throws Exception{Path folder=Path.of("target/react-evidence");Files.createDirectories(folder);Files.write(folder.resolve(name),((TakesScreenshot)driver).getScreenshotAs(OutputType.BYTES));}
 @Test void loginDashboardAndLogout()throws Exception{
  driver.get(base+"/login");driver.findElement(By.id("username")).sendKeys("customer-demo");driver.findElement(By.id("password")).sendKeys("incorrect-password");driver.findElement(By.cssSelector("button[type=submit]")).click();wait.until(ExpectedConditions.textToBePresentInElementLocated(By.cssSelector("[role=alert]"),"incorrect"));
  login();wait.until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector(".wallet-balance")));assertThat(driver.findElement(By.cssSelector("main")).getText()).contains("5,000.00","1,000.00");screenshot("dashboard-demo.png");
  driver.findElement(By.cssSelector(".app-footer button")).click();wait.until(d->d.getCurrentUrl().endsWith("/login"));driver.get(base+"/dashboard");wait.until(d->d.getCurrentUrl().contains("/login"));
 }
 @Test void quotePersistsAcrossReloadAndCanBeCancelled()throws Exception{
  login();create();String url=driver.getCurrentUrl();assertThat(driver.findElement(By.cssSelector(".quote-receive")).getText()).contains("1,167.66");screenshot("quote-review-demo.png");
  driver.navigate().refresh();wait.until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector(".quote-receive")));assertThat(driver.getCurrentUrl()).isEqualTo(url);assertThat(driver.findElement(By.cssSelector(".quote-receive")).getText()).contains("1,167.66");
  driver.findElement(By.xpath("//button[contains(.,'Cancel quote')]")).click();wait.until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector(".badge-cancelled")));assertThat(driver.findElements(By.xpath("//button[contains(.,'Accept quote')]")).size()).isZero();
 }
 @Test void invalidAmountsNeverCreateQuotes(){login();driver.get(base+"/exchange");wait.until(d->!d.findElement(By.id("sourceWallet")).getDomProperty("value").isBlank());WebElement amount=driver.findElement(By.id("sourceAmount"));amount.clear();amount.sendKeys("0.01");driver.findElement(By.cssSelector("button[type=submit]")).click();wait.until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector(".field-error")));assertThat(driver.getCurrentUrl()).endsWith("/exchange");}
 @Test void unavailableSettlementNeverShowsSuccess(){login();create();driver.findElement(By.xpath("//button[contains(.,'Accept quote')]")).click();wait.until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector("[role=alert]")));assertThat(driver.getCurrentUrl()).contains("/exchange/quote/");assertThat(driver.findElement(By.cssSelector("[role=alert]")).getText()).contains("M3 is not connected");}
 @Test void mobileHistoryAndForeignAccountAreSafe()throws Exception{
  login();driver.manage().window().setSize(new Dimension(390,844));driver.get(base+"/transactions");wait.until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector(".empty-state")));assertThat(driver.findElement(By.cssSelector(".empty-state")).getText()).contains("does not move money");
  long overflow=((Number)((JavascriptExecutor)driver).executeScript("return document.documentElement.scrollWidth-window.innerWidth")).longValue();assertThat(overflow).isLessThanOrEqualTo(1);screenshot("history-mobile-demo.png");
  driver.get(base+"/transactions?walletId=3");wait.until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector("[role=alert]")));assertThat(driver.findElements(By.cssSelector("tbody tr"))).isEmpty();
 }
 @Test void expiredQuoteDisablesAcceptance(){login();create();new WebDriverWait(driver,Duration.ofSeconds(70)).until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector(".badge-expired")));assertThat(driver.findElements(By.xpath("//button[contains(.,'Accept quote')]")).size()).isZero();assertThat(driver.findElement(By.cssSelector(".quote-card")).getText()).contains("Get a new quote");}
}
