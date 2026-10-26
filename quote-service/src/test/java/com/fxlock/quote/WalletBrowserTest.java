package com.fxlock.quote;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.openqa.selenium.*;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import static org.assertj.core.api.Assertions.*;

/** Actual M3 static assets, an isolated M2 session/H2 database, and stub M3 financial APIs. */
@EnabledIfSystemProperty(named = "runBrowser", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"team", "test"})
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:m3walletbrowser;MODE=Oracle;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password="
})
class WalletBrowserTest {
    static final String ID = "780d22c5-83d6-4bc0-b6c5-4de7c5d97288";
    static HttpServer server;
    @org.springframework.beans.factory.annotation.Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    static volatile org.springframework.jdbc.core.JdbcTemplate fixtureDatabase;
    @org.springframework.test.context.bean.override.mockito.MockitoBean RateGateway rates;
    static final java.util.concurrent.ConcurrentMap<String, DemoAccount> accounts = new java.util.concurrent.ConcurrentHashMap<>();
    record DemoAccount(String customerRef, String fullName, String password) {
        @Override public String toString() { return "DemoAccount[credentials redacted]"; }
    }
    static final AtomicInteger walletReads = new AtomicInteger(), acceptances = new AtomicInteger();
    static final AtomicReference<String> customer = new AtomicReference<>(), token = new AtomicReference<>(), key = new AtomicReference<>();
    @LocalServerPort int port;
    WebDriver driver;
    WebDriverWait wait;

    @DynamicPropertySource static void m3(DynamicPropertyRegistry registry) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var assets = Map.of("/settlement.html", "text/html", "/wallets.html", "text/html",
            "/css/settlement.css", "text/css", "/js/settlement.js", "application/javascript",
            "/css/wallets.css", "text/css", "/js/wallets.js", "application/javascript");
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String body;
            int status = 200;
            if (path.equals("/dashboard/login")) {
                exchange.getResponseHeaders().set("Content-Type", "text/html");
                body = "<h1 id='dashboard-fixture'>Dashboard handoff requested</h1>";
            } else if (assets.containsKey(path)) {
                body = Files.readString(Path.of("..", "settlement-service", "src", "main", "resources", "static", path.substring(1)));
                exchange.getResponseHeaders().set("Content-Type", assets.get(path));
            } else {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                customer.set(exchange.getRequestHeaders().getFirst("X-Customer-Ref"));
                token.set(exchange.getRequestHeaders().getFirst("X-Internal-Token"));
                if (path.startsWith("/internal/accounts/")) {
                    var json = new com.fasterxml.jackson.databind.ObjectMapper();
                    var input = json.readTree(exchange.getRequestBody().readAllBytes());
                    String email = input.path("email").asText();
                    if (path.endsWith("/register")) {
                        String ref = "CUST-browser-" + java.util.UUID.randomUUID();
                        var account = new DemoAccount(ref, input.path("fullName").asText(), input.path("password").asText());
                        fixtureDatabase.update("INSERT INTO customer(customer_ref,full_name,email,status) VALUES (?,?,?,'ACTIVE')", ref, account.fullName(), email);
                        Long id = fixtureDatabase.queryForObject("SELECT customer_id FROM customer WHERE customer_ref=?", Long.class, ref);
                        fixtureDatabase.update("INSERT INTO customer_account(customer_id,account_status) VALUES (?,'ACTIVE')",id);
                        fixtureDatabase.update("INSERT INTO wallet(customer_id,currency_code,balance,status) VALUES (?,'EUR',0,'ACTIVE')", id);
                        fixtureDatabase.update("INSERT INTO wallet(customer_id,currency_code,balance,status) VALUES (?,'USD',0,'ACTIVE')", id);
                        accounts.put(email, account);
                        status = 201; body = json.writeValueAsString(Map.of("customerRef", ref));
                    } else {
                        var account = accounts.get(email);
                        if (account != null && account.password().equals(input.path("password").asText())) {
                            body = json.writeValueAsString(Map.of("customerRef", account.customerRef(), "fullName", account.fullName(), "email", email));
                        } else {
                            status = 401; body = "{\"code\":\"INVALID_CREDENTIALS\"}";
                        }
                    }
                } else if (path.equals("/api/v1/wallets") && customer.get() != null && customer.get().startsWith("CUST-browser-")) {
                    walletReads.incrementAndGet();
                    body = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(fixtureDatabase.queryForList(
                        "SELECT w.wallet_id AS \"walletId\", w.currency_code AS \"currencyCode\", w.balance AS \"balance\" FROM wallet w JOIN customer c ON c.customer_id=w.customer_id WHERE c.customer_ref=? ORDER BY w.currency_code", customer.get()));
                } else if (path.equals("/api/v1/wallets")) {
                    walletReads.incrementAndGet();
                    body = "CUST002".equals(customer.get())
                        ? "[{\"walletId\":3,\"currencyCode\":\"EUR\",\"balance\":22.22},{\"walletId\":4,\"currencyCode\":\"USD\",\"balance\":33.33}]"
                        : "[{\"walletId\":1,\"currencyCode\":\"EUR\",\"balance\":123.45},{\"walletId\":2,\"currencyCode\":\"USD\",\"balance\":67.89}]";
                } else {
                    body = "{\"settlementId\":\"" + ID + "\",\"quoteId\":\"" + ID
                        + "\",\"status\":\"COMPLETED\",\"sourceCurrency\":\"EUR\",\"sourceAmount\":10,"
                        + "\"targetCurrency\":\"USD\",\"targetAmount\":11.6,\"ledgerEntries\":[]}";
                    if (exchange.getRequestMethod().equals("POST")) {
                        acceptances.incrementAndGet();
                        key.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
                        exchange.getRequestBody().readAllBytes();
                        status = 201;
                    }
                }
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        registry.add("fxlock.settlement-base-url", () -> "http://127.0.0.1:" + server.getAddress().getPort());
        registry.add("fxlock.account-service-url", () -> "http://127.0.0.1:" + server.getAddress().getPort());
        registry.add("fxlock.dashboard-url", () -> "http://127.0.0.1:" + server.getAddress().getPort());
        registry.add("fxlock.internal-token", () -> "browser-test-token");
    }

    @BeforeEach void browser() {
        fixtureDatabase = jdbc;
        jdbc.execute("CREATE TABLE IF NOT EXISTS customer_account (customer_id NUMBER PRIMARY KEY, account_status VARCHAR2(20))");
        jdbc.update("INSERT INTO customer_account(customer_id,account_status) SELECT customer_id,'ACTIVE' FROM customer c WHERE NOT EXISTS (SELECT 1 FROM customer_account a WHERE a.customer_id=c.customer_id)");
        org.mockito.Mockito.when(rates.trustedRate()).thenAnswer(ignored -> QuoteServiceTest.rate(java.time.Instant.now().minusSeconds(1),"1.17",true,true,false,true,"0"));
        String binary = System.getenv("BROWSER_DRIVER_PATH");
        if (binary != null) { System.setProperty("webdriver.chrome.driver", binary); }
        var options = new ChromeOptions();
        options.addArguments("--headless=new", "--window-size=1365,1000");
        driver = new ChromeDriver(options);
        wait = new WebDriverWait(driver, Duration.ofSeconds(15));
        login("CUST001");
    }

    void login(String reference) {
        String email=reference.toLowerCase(java.util.Locale.ROOT)+"@example.test";
        accounts.put(email,new DemoAccount(reference,"Registered Fixture Customer","learn-fxlock"));
        driver.get("http://localhost:" + port + "/login");
        driver.findElement(By.name("username")).sendKeys(email);
        driver.findElement(By.name("password")).sendKeys("learn-fxlock");
        driver.findElement(By.name("password")).sendKeys(Keys.ENTER);
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("dashboard-fixture")));
        driver.get("http://localhost:"+port+"/");
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("quote-form")));
    }

    @AfterEach void closeBrowser() { if (driver != null) { driver.quit(); } }
    @AfterAll static void stopM3() { if (server != null) { server.stop(0); } }

    @Test void registeredCustomerCanSelectAnotherCustomersWalletAndReviewAReverseQuote() {
        wait.until(d -> new org.openqa.selenium.support.ui.Select(d.findElement(By.id("target-wallet"))).getOptions().stream()
            .anyMatch(option -> "4".equals(option.getDomProperty("value"))));
        var direction=new org.openqa.selenium.support.ui.Select(driver.findElement(By.id("direction")));
        direction.selectByValue("USD-EUR");
        var source=new org.openqa.selenium.support.ui.Select(driver.findElement(By.id("source-wallet")));
        assertThat(source.getOptions()).hasSize(1);
        assertThat(source.getFirstSelectedOption().getDomProperty("value")).isEqualTo("2");
        var destination=new org.openqa.selenium.support.ui.Select(driver.findElement(By.id("target-wallet")));
        destination.selectByValue("3");
        assertThat(destination.getFirstSelectedOption().getText()).contains("CUST002","EUR").doesNotContain("@");
        var amount=driver.findElement(By.id("amount")); amount.clear(); amount.sendKeys("100.00");
        driver.findElement(By.id("create")).click();
        wait.until(ExpectedConditions.textToBe(By.id("target-amount"),"85.30"));
        assertThat(driver.findElement(By.id("source-amount")).getText()).contains("USD");
        assertThat(driver.findElement(By.id("review-target-currency")).getText()).isEqualTo("EUR");
        assertThat(driver.findElement(By.id("review-target-wallet")).getText()).contains("CUST002","#3");
        destination.selectByValue("1");
        assertThat(driver.findElement(By.id("review-target-wallet")).getText()).contains("CUST002","#3");
    }

    @Test void viewWalletsOpensM3PageLoadsBalancesRefreshesAndLooksUpSettlement() throws Exception {
        driver.get("http://localhost:" + port + "/settlement.html?settlementId=" + ID);
        wait.until(ExpectedConditions.elementToBeClickable(By.linkText("View wallets"))).click();
        wait.until(ExpectedConditions.urlContains("/wallets.html"));
        wait.until(ExpectedConditions.textToBePresentInElementLocated(By.id("walletList"), "123.45"));
        assertThat(driver.findElement(By.id("walletList")).getText()).contains("EUR", "USD", "67.89");
        assertThat(driver.findElement(By.id("customerBadge")).getText()).contains("CUST001");
        assertThat(driver.findElements(By.id("quote-form"))).isEmpty();
        assertThat(token.get()).isEqualTo("browser-test-token");
        assertThat(driver.getPageSource()).doesNotContain("browser-test-token");
        int reads = walletReads.get();
        driver.findElement(By.id("refreshButton")).click();
        wait.until(ignored -> walletReads.get() > reads);
        driver.findElement(By.id("settlementId")).sendKeys(ID);
        driver.findElement(By.id("lookupButton")).click();
        wait.until(ExpectedConditions.textToBePresentInElementLocated(By.id("settlementDetails"), "COMPLETED"));
        Path screenshot = Path.of("target", "browser-evidence", "m3-wallet-navigation.png");
        Files.createDirectories(screenshot.getParent());
        Files.write(screenshot, ((TakesScreenshot) driver).getScreenshotAs(OutputType.BYTES));
    }

    @Test void walletAcceptanceUsesCsrfAndCustomerSwitchDoesNotRestoreOtherCustomersAttempt() {
        driver.get("http://localhost:" + port + "/wallets.html");
        wait.until(ExpectedConditions.textToBePresentInElementLocated(By.id("walletList"), "123.45"));
        int previous = acceptances.get();
        driver.findElement(By.id("acceptQuoteId")).sendKeys(ID);
        driver.findElement(By.id("acceptButton")).click();
        wait.until(ExpectedConditions.alertIsPresent()).accept();
        wait.until(ExpectedConditions.textToBePresentInElementLocated(By.id("acceptMessage"), "Conversion completed"));
        assertThat(acceptances.get()).isEqualTo(previous + 1);
        assertThat(key.get()).isNotBlank();
        assertThat(customer.get()).isEqualTo("CUST001");
        login("CUST002");
        driver.get("http://localhost:" + port + "/wallets.html");
        wait.until(ExpectedConditions.textToBePresentInElementLocated(By.id("walletList"), "22.22"));
        assertThat(driver.findElement(By.id("customerBadge")).getText()).contains("CUST002");
        assertThat(driver.findElement(By.id("acceptQuoteId")).getDomProperty("value")).isEmpty();
        assertThat(driver.findElement(By.id("acceptButton")).getText()).isEqualTo("Accept quote");
    }
    @Test void signupAndEmailLoginOpenTheNewCustomersOwnZeroBalanceWallets() {
        driver.manage().deleteAllCookies();
        driver.get("http://localhost:" + port + "/login");
        assertThat(driver.findElement(By.cssSelector("label[for='username']")).getText()).isEqualTo("Email");
        driver.findElement(By.linkText("Sign up")).click();
        String email = "browser." + java.util.UUID.randomUUID() + "@example.com";
        String password = "  BrowserPassword!2026  ";
        driver.findElement(By.name("fullName")).sendKeys("Browser Customer");
        driver.findElement(By.name("email")).sendKeys(email);
        driver.findElement(By.name("password")).sendKeys(password);
        driver.findElement(By.name("confirmPassword")).sendKeys(password);
        driver.findElement(By.cssSelector("button[type='submit']")).click();
        wait.until(ExpectedConditions.urlContains("registered"));
        assertThat(accounts.get(email).fullName()).isEqualTo("Browser Customer");
        assertThat(accounts.get(email).password()).isEqualTo(password);
        driver.findElement(By.name("username")).sendKeys(email);
        driver.findElement(By.name("password")).sendKeys("incorrect-password", Keys.ENTER);
        wait.until(ExpectedConditions.urlContains("error"));
        assertThat(driver.findElement(By.name("password")).getDomProperty("value")).isEmpty();
        driver.findElement(By.name("username")).sendKeys(email);
        driver.findElement(By.name("password")).sendKeys(password, Keys.ENTER);
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("dashboard-fixture")));
        // The full secure handoff has separate tests; this fixture checks retained M2 navigation.
        driver.get("http://localhost:" + port + "/");
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("quote-form")));
        driver.get("http://localhost:" + port + "/wallets.html");
        wait.until(ExpectedConditions.textToBePresentInElementLocated(By.id("customerBadge"), accounts.get(email).customerRef()));
        wait.until(ExpectedConditions.textToBePresentInElementLocated(By.id("walletList"), "EUR"));
        assertThat(driver.findElement(By.id("walletList")).getText()).contains("USD").doesNotContain("123.45", "67.89");
        assertThat(customer.get()).isEqualTo(accounts.get(email).customerRef());
    }

}
