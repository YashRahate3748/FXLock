package com.fxlock.quote;
import java.nio.file.*;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.openqa.selenium.*;
import org.openqa.selenium.chrome.*;
import org.openqa.selenium.support.ui.*;
import static org.assertj.core.api.Assertions.*;
/** Opt-in read-only UI smoke check. Does not request, accept or cancel any quote. */
@EnabledIfSystemProperty(named="captureOracleFrontend",matches="true")
class ReactOracleReadOnlyPreviewTest {
 @Test void oracleWorkspaceRenders()throws Exception{
  var options=new ChromeOptions();options.addArguments("--headless=new","--window-size=1440,1100");
  WebDriver driver=new ChromeDriver(options);
  try{
   var wait=new WebDriverWait(driver,Duration.ofSeconds(20));driver.get("http://localhost:5173/login");
   driver.findElement(By.id("username")).sendKeys("customer-demo");driver.findElement(By.id("password")).sendKeys("learn-fxlock");
   driver.findElement(By.cssSelector("button[type=submit]")).click();wait.until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector(".wallet-balance")));
   assertThat(driver.findElements(By.cssSelector(".wallet-card"))).isNotEmpty();
   Path out=Path.of("target/react-evidence");Files.createDirectories(out);
   Files.write(out.resolve("dashboard-oracle.png"),((TakesScreenshot)driver).getScreenshotAs(OutputType.BYTES));
   driver.get("http://localhost:5173/transactions");wait.until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector(".history-search")));
   wait.until(d->d.findElements(By.cssSelector(".loading")).isEmpty());assertThat(driver.findElements(By.cssSelector("[role=alert]"))).isEmpty();
   Files.write(out.resolve("history-oracle.png"),((TakesScreenshot)driver).getScreenshotAs(OutputType.BYTES));
  }finally{driver.quit();}
 }
}
