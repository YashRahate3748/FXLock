// UI contracts are intercepted; this test never creates customers or financial records.
import assert from "node:assert/strict";
import {createRequire} from "node:module";
import {createServer} from "vite";
const require = createRequire(import.meta.url);
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || "playwright");
const server = await createServer({server:{port:5197,strictPort:true,open:false}});
await server.listen();
let browser;
try {
 browser=await chromium.launch({headless:true, channel: "chrome"});
 const context=await browser.newContext({viewport:{width:1440,height:1000}});
 const page=await context.newPage();
 page.setDefaultTimeout(10000); page.setDefaultNavigationTimeout(10000); const errors=[]; page.on("pageerror",e=>errors.push(e.message));
 let signedIn=false, acceptKeys=[], attempts=0, latestQuote=null, cancelled=false;
 const home='<html><head><meta name="csrf-token" content="session-token"><meta name="csrf-header" content="X-CSRF-TOKEN"></head><body><header><span>Currency quotes · <span>customer-fixture</span></span></header><form id="quote-form"><input id="amount" min="1.00" max="10000.00"></form><p class="hint">Configured spread: <span>20</span></p></body></html>';
 const login='<html><body><form action="/login"><input name="_csrf" value="login-token"></form></body></html>';
 const history=[{entryId:"FUNDING:deposit-1",ledgerId:null,settlementId:null,transactionRef:"deposit-1",transactionType:"DEPOSIT",walletId:"1",customerName:"Fixture Customer",currency:"EUR",amountCredited:"1000.00",amountDebited:"0.00",updatedBalance:"1000.00",transactionDate:"2026-10-26T00:00:00Z"},
 {entryId:"FX:2",ledgerId:"2",settlementId:"settlement-fixture",transactionRef:"settlement-fixture",transactionType:"FX_DEBIT",walletId:"2",customerName:"Fixture Customer",currency:"USD",amountCredited:"0.00",amountDebited:"10.00",updatedBalance:"990.00",transactionDate:"2026-10-26T01:00:00Z"}];
 const json=(route,data,status=200)=>route.fulfill({status,contentType:"application/json",body:JSON.stringify(data)});
 await context.route("**/m2/**",async route=>{
  const request=route.request(), url=new URL(request.url()), p=url.pathname.slice(3), method=request.method();
  if(p==="/login" || p==="/demo-login") {
   if(method==="GET") return route.fulfill({contentType:"text/html",body:login});
   const body=new URLSearchParams(request.postData());
   assert.equal(body.get("_csrf"),"login-token");
   if(body.get("password")!=="fixture-password") return route.fulfill({contentType:"text/html",body:login});
   signedIn=true; return route.fulfill({status:302,headers:{location:"/_test/authenticated"}});
  }
  if(!signedIn) return p==="/" ? route.fulfill({contentType:"text/html",body:login}) : json(route,{message:"Sign in"},401);
  if(p==="/") return route.fulfill({contentType:"text/html",body:home});
  if(p==="/api/v1/wallets") return json(route,[{walletId:1,currencyCode:"EUR",balance:"1000.0000"},{walletId:2,currencyCode:"USD",balance:"990.0000"}]);
  if(p==="/api/transaction-history/accounts") return json(route,[{walletId:"1",currency:"EUR",status:"ACTIVE"},{walletId:"2",currency:"USD",status:"ACTIVE"}]);
  if(p==="/api/transaction-history") {
   const rows=history.filter(e=>!url.searchParams.get("walletId") || e.walletId===url.searchParams.get("walletId"));
   const size=Number(url.searchParams.get("size")||20), index=Number(url.searchParams.get("page")||0);
   return json(route,{entries:rows.slice(index*size,(index+1)*size),total:rows.length,hasNext:false,page:index,size});
  }
  if(p==="/wallets/quote-options") return json(route,[{walletId:"1",currency:"EUR",canDebit:true,label:"My EUR"},{walletId:"2",currency:"USD",canDebit:true,label:"My USD"},{walletId:"3",currency:"EUR",canDebit:false,label:"Other customer"}]);
  if(p==="/quotes" && method==="POST") {
   assert.equal(request.headers()["x-csrf-token"],"session-token");
   const input=request.postDataJSON();
   latestQuote={...input,quoteId:"quote-fixture",status:"OPEN",targetAmount:"8.40",offeredRate:"0.84",spreadBps:"20",createdAt:new Date().toISOString(),expiresAt:new Date(Date.now()+60000).toISOString(),confidence:{score:"95",label:"High",reasons:["Fresh trusted rate"]}};
   return json(route,{quote:latestQuote,serverTime:new Date().toISOString()});
  }
  if(p==="/quotes/quote-fixture") return json(route,{quote:{...latestQuote,status:cancelled?"CANCELLED":"OPEN"},serverTime:new Date().toISOString()});
  if(p.endsWith("/cancel")) {cancelled=true;return json(route,{quote:{...latestQuote,status:"CANCELLED"},serverTime:new Date().toISOString()});}
  if(p.endsWith("/accept")) {
   acceptKeys.push(request.headers()["idempotency-key"]);
   if(++attempts===1) return json(route,{message:"Confirmation unavailable"},503);
   return json(route,{status:"COMPLETED",settlementId:"settlement-fixture"});
  }
  if(p==="/api/v1/settlements/settlement-fixture/details") return json(route,{settlementId:"settlement-fixture",quoteId:"quote-fixture",status:"COMPLETED",sourceCurrency:"USD",targetCurrency:"EUR",sourceAmount:"10.00",targetAmount:"8.40",lockedCustomerRate:"0.84",completedAt:"2026-10-26T01:00:00Z",ledgerEntries:[{ledgerId:1,walletId:2,entryType:"DEBIT",currencyCode:"USD",amount:"10.00",balanceBefore:"1000.00",balanceAfter:"990.00"},{ledgerId:2,walletId:3,entryType:"CREDIT",currencyCode:"EUR",amount:"8.40",balanceBefore:"0.00",balanceAfter:"8.40"}]});
  if(p==="/logout") {signedIn=false;return route.fulfill({status:302,headers:{location:"/_test/signed-out"}});}
  return json(route,{message:"Unexpected endpoint: "+p},404);
 });
 await context.route("**/m3/**",route=>json(route,{message:"No dashboard session"},401));
 await page.goto("http://localhost:5197/");
 await page.getByRole("heading",{name:/A clear amount/}).waitFor();
 await page.goto("http://localhost:5197/dashboard");
 await page.waitForURL("**/login");
 await page.getByLabel("Email",{exact:true}).fill("fixture@example.com");
 await page.getByLabel("Password",{exact:true}).fill("wrong");
 await page.getByRole("button",{name:"Sign in",exact:true}).click();
 await page.getByText("The email or password is incorrect.",{exact:true}).waitFor();
 await page.getByLabel("Password",{exact:true}).fill("fixture-password");
 await page.getByRole("button",{name:"Sign in",exact:true}).click();
 await page.waitForURL("**/dashboard");
 await page.locator(".wallet-balance").filter({hasText:"1,000.00"}).waitFor();
 await page.reload(); await page.getByRole("heading",{name:/Good to see you/}).waitFor();
 await page.goto("http://localhost:5197/exchange");
 await page.getByLabel("Conversion direction").selectOption("USD");
 await page.getByLabel("Sender USD wallet").selectOption("2");
 await page.getByLabel("Receiving EUR wallet").selectOption("3");
 await page.getByLabel("You send",{exact:true}).fill("10.00");
 await page.getByRole("button",{name:/Get my fixed quote/}).click();
 await page.waitForURL("**/exchange/quote/quote-fixture");
 assert.equal(latestQuote.sourceCurrency,"USD"); assert.equal(latestQuote.targetCurrency,"EUR");
 assert.equal(latestQuote.targetWalletId,"3");
 await page.getByText("1 USD = 0.84 EUR",{exact:true}).waitFor();
 await page.getByRole("button",{name:/Accept quote/}).click();
 await page.getByRole("button",{name:/Retry with the same key/}).waitFor();
 await page.reload();
 await page.getByRole("button",{name:/Retry with the same key/}).click();
 await page.waitForURL("**/transfer/result/settlement-fixture");
 assert.equal(acceptKeys.length,2); assert.equal(acceptKeys[0],acceptKeys[1]);
 await page.getByText("0.84 EUR per USD",{exact:true}).waitFor();
 await page.getByRole("heading",{name:"Wallet ledger"}).waitFor();
 await page.goto("http://localhost:5197/exchange");
 await page.getByLabel("Sender EUR wallet").selectOption("1");
 await page.getByLabel("Receiving USD wallet").selectOption("2");
 await page.getByLabel("You send",{exact:true}).fill("10.00");
 await page.getByRole("button",{name:/Get my fixed quote/}).click();
 await page.waitForURL("**/exchange/quote/quote-fixture");
 assert.equal(latestQuote.sourceCurrency,"EUR"); assert.equal(latestQuote.targetCurrency,"USD");
 await page.getByRole("button",{name:"Cancel quote",exact:true}).click();
 await page.getByRole("button",{name:/Accept quote/}).waitFor({state:"hidden"});
 assert.equal(cancelled,true);
 cancelled=false; latestQuote.expiresAt=new Date(Date.now()-1000).toISOString();
 await page.reload(); await page.getByText("This offer has expired",{exact:true}).waitFor();
 assert.equal(await page.getByRole("button",{name:/Accept quote/}).count(),0);
 await page.goto("http://localhost:5197/transactions");
 await page.getByText(/DEPOSIT ·/).waitFor();
 await page.getByLabel("Reference or customer name").fill("deposit-1");
 await page.getByRole("button",{name:"Apply filters"}).click();
 await page.getByText("Showing 1–1 of 1 entries",{exact:true}).waitFor();
 assert.equal(await page.locator('a[href="/transactions/null"]').count(),0);
 await page.setViewportSize({width:390,height:844});
 await page.goto("http://localhost:5197/dashboard");
 await page.getByRole("heading",{name:/Good to see you/}).waitFor();
 assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth),true);
 await page.screenshot({path:"verification/compatible-dashboard-mobile.png",fullPage:true});
 await page.setViewportSize({width:1440,height:1000});
 await page.screenshot({path:"verification/compatible-dashboard-desktop.png",fullPage:true});
 await page.getByRole("button",{name:"Sign out",exact:true}).click(); await page.waitForURL("**/login");
 assert.deepEqual(errors,[]);
 console.log("Browser checks passed: landing, protected routes, failed/email login, reload, both conversion directions, cancel, expiry, other-customer wallet, CSRF, uncertain retry key after reload, receipt/ledger, funding history, filters, mobile layout and logout. No live backend writes.");
 await context.close();
} finally {if(browser) await browser.close();await server.close();}
