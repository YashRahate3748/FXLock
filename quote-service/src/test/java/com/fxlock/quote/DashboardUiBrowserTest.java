package com.fxlock.quote;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.time.Duration;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.openqa.selenium.*;
import org.openqa.selenium.chrome.*;
import org.openqa.selenium.support.ui.*;
import static org.assertj.core.api.Assertions.*;

/** Tests the production React bundle against an HTTP fixture, never a real customer database. */
@EnabledIfSystemProperty(named = "runDashboardBrowser", matches = "true")
class DashboardUiBrowserTest {
    HttpServer fixture;
    WebDriver browser;
    WebDriverWait wait;
    ObjectMapper json = new ObjectMapper();
    BigDecimal eur = new BigDecimal("100.0000");
    BigDecimal usd = new BigDecimal("0.0000");
    Map<String, Object> saved;
    String savedKey;
    volatile boolean loseResponse;
    volatile boolean dataFailure;
    volatile long responseDelay;
    volatile boolean historyFailure;
    AtomicInteger submissions = new AtomicInteger();
    String base;

    @BeforeEach void setup() throws Exception {
        String driver = System.getProperty("dashboard.driver");
        Assumptions.assumeTrue(driver != null && Files.isRegularFile(Path.of(driver)), "Explicit local driver required; Selenium Manager downloads are prohibited");
        System.setProperty("webdriver.chrome.driver", driver);
        fixture = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        fixture.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            int status = 200;
            String type = "application/json";
            byte[] body;
            if (path.equals("/api/dashboard")) {
                if (dataFailure) { status = 503; body = json.writeValueAsBytes(Map.of("code", "UNAVAILABLE")); }
                else body = json.writeValueAsBytes(Map.of("account", Map.of("fullName", "Fixture Customer", "email", "fixture@example.test", "customerRef", "fixture", "accountStatus", "ACTIVE"),
                        "wallets", List.of(wallet("10", "EUR", eur, "ACTIVE"), wallet("11", "USD", usd, "ACTIVE"), wallet("12", "EUR", BigDecimal.TEN, "BLOCKED")),
                        "csrfToken", "fixture-csrf", "quoteUrl", base + "/fixture-quote"));
            } else if (path.equals("/api/dashboard/history")) {
                if (historyFailure) { status=503; body=json.writeValueAsBytes(Map.of("code","HISTORY_UNAVAILABLE")); }
                else {
                    var params=new HashMap<String,String>();
                    String query=exchange.getRequestURI().getRawQuery();
                    if (query!=null) for (String pair : query.split("&")) {
                        String[] parts=pair.split("=",2);
                        params.put(parts[0],parts.length==2 ? java.net.URLDecoder.decode(parts[1],java.nio.charset.StandardCharsets.UTF_8) : "");
                    }
                    int page=Integer.parseInt(params.getOrDefault("page","0"));
                    int size=Integer.parseInt(params.getOrDefault("size","20"));
                    var entries=new ArrayList<Map<String,Object>>();
                    for (int i=0;i<22;i++) {
                        String wallet=i%2==0 ? "10" : "11";
                        if (params.containsKey("walletId") && !params.get("walletId").equals(wallet)) continue;
                        var row=new HashMap<String,Object>();
                        row.put("entryId","fixture:"+i); row.put("ledgerId",i<2 ? null : String.valueOf(i));
                        row.put("settlementId",i<2 ? null : "settlement-"+i); row.put("walletId",wallet);
                        row.put("currency",wallet.equals("10") ? "EUR" : "USD");
                        row.put("transactionRef",i<2 ? "funding-"+i : "settlement-"+i);
                        row.put("transactionType",i==0 ? "DEPOSIT" : i==1 ? "WITHDRAWAL" : i%2==0 ? "FX_DEBIT" : "FX_CREDIT");
                        row.put("transactionDate","2026-10-24T10:00:00Z");
                        row.put("amountCredited",i%2==0 ? "10.0000" : "0.0000");
                        row.put("amountDebited",i%2==0 ? "0.0000" : "10.0000");
                        row.put("updatedBalance","0.0000"); entries.add(row);
                    }
                    int start=Math.min(page*size,entries.size());
                    body=json.writeValueAsBytes(Map.of("entries",entries.subList(start,Math.min(start+size,entries.size())),"page",page,"size",size,"total",entries.size(),"hasNext",start+size<entries.size()));
                }
            } else if (path.equals("/api/dashboard/funding") && exchange.getRequestMethod().equals("POST")) {
                submissions.incrementAndGet();
                var request = json.readTree(exchange.getRequestBody());
                String key = exchange.getRequestHeaders().getFirst("Idempotency-Key");
                if (!" x ".equals(request.path("password").asText())) {
                    status = 401; body = json.writeValueAsBytes(Map.of("code", "CONFIRMATION_REJECTED"));
                } else {
                    if (!key.equals(savedKey)) {
                        BigDecimal amount = new BigDecimal(request.path("amount").asText());
                        boolean deposit = "DEPOSIT".equals(request.path("operation").asText());
                        String wallet = request.path("walletId").asText();
                        if (wallet.equals("10")) eur = deposit ? eur.add(amount) : eur.subtract(amount);
                        else usd = deposit ? usd.add(amount) : usd.subtract(amount);
                        savedKey = key;
                        saved = Map.of("transactionRef", "fixture-transaction", "operation", request.path("operation").asText(), "amount", amount.toPlainString(), "currency", wallet.equals("10") ? "EUR" : "USD", "walletId", wallet, "idempotencyKey", key);
                    }
                    if (responseDelay > 0) try { Thread.sleep(responseDelay); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                    if (loseResponse) { status = 503; body = json.writeValueAsBytes(Map.of("code", "INTERNAL_ERROR")); }
                    else body = json.writeValueAsBytes(saved);
                }
            } else if (path.startsWith("/api/dashboard/funding/")) {
                status = saved == null ? 404 : 200;
                body = json.writeValueAsBytes(saved == null ? Map.of("code", "OUTCOME_NOT_FOUND") : saved);
            } else if (path.equals("/api/dashboard/logout")) {
                body = json.writeValueAsBytes(Map.of("logoutUrl", base + "/fixture-logout"));
            } else if (path.startsWith("/fixture-")) {
                type = "text/html"; body = ("<h1>" + path + "</h1>").getBytes();
            } else {
                Path root = Path.of("../frontend/dist").toAbsolutePath().normalize();
                Path file = path.equals("/dashboard/") || path.equals("/dashboard/history/") ? root.resolve("index.html") : root.resolve(path.substring("/dashboard/".length())).normalize();
                if (!file.startsWith(root) || !Files.isRegularFile(file)) { exchange.sendResponseHeaders(404, -1); exchange.close(); return; }
                type = file.toString().endsWith(".js") ? "text/javascript" : file.toString().endsWith(".css") ? "text/css" : "text/html";
                body = Files.readAllBytes(file);
            }
            exchange.getResponseHeaders().set("Content-Type", type);
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        fixture.start();
        base = "http://localhost:" + fixture.getAddress().getPort();
        var options = new ChromeOptions();
        options.addArguments("--headless=new", "--window-size=1365,1000");
        browser = new ChromeDriver(options);
        wait = new WebDriverWait(browser, Duration.ofSeconds(10));
        browser.get(base + "/dashboard/");
        wait.until(ExpectedConditions.textToBePresentInElementLocated(By.tagName("h1"), "Fixture Customer"));
    }

    Map<String, String> wallet(String id, String currency, BigDecimal balance, String status) { return Map.of("walletId", id, "currency", currency, "balance", balance.toPlainString(), "status", status); }
    WebElement button(String label) { return browser.findElement(By.xpath("//button[normalize-space()='" + label + "']")); }
    void open(String label) { button(label).click(); wait.until(ExpectedConditions.visibilityOfElementLocated(By.tagName("dialog"))); }
    void input(String amount, String password) {
        var field = browser.findElement(By.id("funding-amount")); field.clear(); field.sendKeys(amount);
        browser.findElement(By.id("funding-password")).sendKeys(password);
    }
    void complete() { button("Complete").click(); }
    void closed() { wait.until(ExpectedConditions.numberOfElementsToBe(By.tagName("dialog"), 0)); }
    @AfterEach void stop() { if (browser != null) browser.quit(); if (fixture != null) fixture.stop(0); }

    @Test void walletSelectionCurrencyCancellationFocusAndEscape() {
        open("Deposit money");
        assertThat(browser.switchTo().activeElement().getDomAttribute("id")).isEqualTo("funding-wallet");
        var select = new Select(browser.findElement(By.id("funding-wallet")));
        assertThat(select.getOptions()).hasSize(2);
        assertThat(select.getOptions().get(1).getText()).contains("USD", "0.0000");
        select.selectByValue("11");
        assertThat(browser.findElement(By.cssSelector("label[for='funding-amount']")).getText()).contains("USD");
        input("10", " x "); button("Cancel").click(); closed();
        assertThat(submissions.get()).isZero();
        assertThat(browser.switchTo().activeElement().getText()).isEqualTo("Deposit money");
        open("Withdraw money");
        assertThat(browser.findElement(By.id("funding-title")).getText()).isEqualTo("Withdraw money");
        browser.switchTo().activeElement().sendKeys(Keys.ESCAPE); closed();
        assertThat(submissions.get()).isZero();
    }

    @Test void rejectionClearsPasswordAndPreservesInputsThenSuccessRefreshesBalances() {
        open("Deposit money"); input("10.20", "wrong"); complete();
        wait.until(ExpectedConditions.textToBePresentInElementLocated(By.cssSelector("dialog [role=alert]"), "incorrect"));
        assertThat(browser.findElement(By.id("funding-password")).getDomProperty("value")).isEmpty();
        assertThat(browser.findElement(By.id("funding-amount")).getDomProperty("value")).isEqualTo("10.20");
        browser.findElement(By.id("funding-password")).sendKeys(" x "); complete(); closed();
        assertThat(browser.findElement(By.cssSelector(".dashboard-success")).getText()).contains("Deposit completed", "10.20 EUR", "fixture-transaction");
        wait.until(ExpectedConditions.textToBePresentInElementLocated(By.cssSelector(".dashboard-balance"), "110.2000"));
        open("Withdraw money"); input("110.20", " x "); complete(); closed();
        wait.until(ExpectedConditions.textToBePresentInElementLocated(By.cssSelector(".dashboard-balance"), "0.0000"));
    }

    @Test void lostResponseSurvivesReloadAndOutcomeCheckWithoutAnotherMutation() {
        loseResponse = true;
        open("Deposit money"); input("10.00", " x "); complete();
        wait.until(ExpectedConditions.textToBePresentInElementLocated(By.cssSelector("dialog [role=alert]"), "uncertain"));
        assertThat(browser.findElement(By.id("funding-password")).getDomProperty("value")).isEmpty();
        assertThat(browser.findElement(By.cssSelector(".dashboard-balance")).getText()).isEqualTo("100.0000");
        String storage = (String) ((JavascriptExecutor) browser).executeScript("return JSON.stringify(sessionStorage)");
        assertThat(storage).contains(savedKey).doesNotContain("password", " x ");
        browser.navigate().refresh();
        wait.until(ExpectedConditions.elementToBeClickable(By.xpath("//button[normalize-space()='Recover funding request']"))).click();
        button("Check outcome").click(); closed();
        assertThat(submissions.get()).isEqualTo(1);
        assertThat( eur).isEqualByComparingTo("110");
    }

    @Test void failedBalanceRefreshIsExplicitAndQuoteAndLogoutNavigateCorrectly() {
        dataFailure = true;
        open("Deposit money"); input("1", " x "); complete(); closed();
        assertThat(browser.findElement(By.cssSelector(".dashboard-success")).getText()).contains("completed");
        assertThat(browser.findElement(By.cssSelector(".dashboard-error")).getText()).contains("out of date");
        assertThat(button("Deposit money").isEnabled()).isFalse();
        browser.findElement(By.linkText("Get a quote")).click();
        wait.until(ExpectedConditions.urlToBe(base + "/fixture-quote"));
        dataFailure = false; browser.get(base + "/dashboard/");
        wait.until(ExpectedConditions.elementToBeClickable(By.xpath("//button[normalize-space()='Logout']"))).click();
        wait.until(ExpectedConditions.urlToBe(base + "/fixture-logout"));
    }

    @Test void repeatedClicksAreDisabledAndUncertainResubmissionReusesTheOriginalIntent() {
        loseResponse = true;
        responseDelay = 1000;
        open("Deposit money"); input("10.00", " x "); complete();
        wait.until(d -> !button("Complete").isEnabled());
        assertThat(button("Cancel").isEnabled()).isFalse();
        button("Complete").click();
        wait.until(ExpectedConditions.textToBePresentInElementLocated(By.cssSelector("dialog [role=alert]"), "uncertain"));
        String original = savedKey;
        assertThat(submissions.get()).isEqualTo(1);
        assertThat(browser.findElement(By.id("funding-password")).getDomProperty("value")).isEmpty();
        assertThat(browser.findElement(By.id("funding-amount")).isEnabled()).isFalse();
        loseResponse = false;
        responseDelay = 0;
        browser.findElement(By.id("funding-password")).sendKeys(" x "); complete(); closed();
        assertThat(savedKey).isEqualTo(original);
        assertThat(submissions.get()).isEqualTo(2);
        assertThat(eur).isEqualByComparingTo("110");
    }
    @Test void dashboardOpensReadableHistoryWithPaginationFilteringAndBackNavigation() {
        browser.findElement(By.linkText("Transaction history")).click();
        wait.until(ExpectedConditions.numberOfElementsToBe(By.cssSelector(".history-table tbody tr"),20));
        assertThat(browser.getCurrentUrl()).endsWith("/dashboard/history/");
        assertThat(browser.findElement(By.cssSelector(".history-table")).getText()).contains("Simulated deposit","FX debit","0.0000 EUR");
        button("Next").click();
        wait.until(ExpectedConditions.numberOfElementsToBe(By.cssSelector(".history-table tbody tr"),2));
        assertThat(button("Next").isEnabled()).isFalse();
        new Select(browser.findElement(By.id("history-wallet"))).selectByValue("11");
        wait.until(ExpectedConditions.numberOfElementsToBe(By.cssSelector(".history-table tbody tr"),11));
        assertThat(browser.findElement(By.cssSelector(".history-table tbody")).getText()).contains("USD").doesNotContain("EUR");
        assertThat(button("Previous").isEnabled()).isFalse();
        new Select(browser.findElement(By.id("history-wallet"))).selectByValue("12");
        wait.until(ExpectedConditions.textToBePresentInElementLocated(By.cssSelector(".history-panel"),"No transactions found"));
        browser.findElement(By.linkText("Back to dashboard")).click();
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.xpath("//button[normalize-space()='Deposit money']")));
    }

    @Test void historyReadFailureShowsAnErrorAndNeverAnEmptySuccessOrInventedBalance() {
        historyFailure=true;
        browser.findElement(By.linkText("Transaction history")).click();
        wait.until(ExpectedConditions.textToBePresentInElementLocated(By.cssSelector("[role=alert]"),"could not be retrieved"));
        assertThat(browser.findElements(By.cssSelector(".history-table"))).isEmpty();
        assertThat(browser.findElement(By.tagName("main")).getText()).doesNotContain("No transactions found","0 wallet entries");
    }
}
