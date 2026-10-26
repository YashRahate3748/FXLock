// Read-only intercepted fixtures; no live financial operations.
import assert from "node:assert/strict";
import {createRequire} from "node:module";
import {createServer} from "vite";
const require=createRequire(import.meta.url),{chromium}=require(process.env.PLAYWRIGHT_MODULE||"playwright");
const vite=await createServer({server:{port:5195,strictPort:true,open:false}});await vite.listen();let browser;
try{
 browser=await chromium.launch({headless:true,channel:"chrome"});const context=await browser.newContext({viewport:{width:1440,height:1100}}),page=await context.newPage();page.setDefaultTimeout(10000);
 let failEuro=false,emptyDollar=false;const calls=[],errors=[];page.on("pageerror",e=>errors.push(e.message));
 const rows=(id,currency)=>Array.from({length:8},(_,i)=>({entryId:id+":"+(8-i),walletId:id,customerName:"Wallet Owner",currency,transactionType:i%2?"WITHDRAWAL":"DEPOSIT",transactionRef:"ref-"+id+"-"+(8-i),amountCredited:i%2?"0.00":String(8-i)+".00",amountDebited:i%2?String(8-i)+".00":"0.00",transactionDate:new Date(Date.UTC(2026,9,26,8-i)).toISOString()}));
 const json=(route,data,status=200)=>route.fulfill({status,contentType:"application/json",body:JSON.stringify(data)});
 const home='<html><head><meta name="csrf-token" content="quote-token"><meta name="csrf-header" content="X-CSRF-TOKEN"></head><body><header><span>Customer <span>owner</span></span></header><form id="quote-form"><input id="amount" min="1.00" max="10000.00"></form><p class="hint">Configured spread: <span>20</span></p></body></html>';
 await context.route("**/m2/**",async route=>{
  const request=route.request(),url=new URL(request.url()),p=url.pathname.slice(3);assert.equal(request.method(),"GET");
  if(p==="/")return route.fulfill({contentType:"text/html",body:home});
  if(p==="/api/v1/wallets")return json(route,[{walletId:"1",currencyCode:"EUR",balance:"1000.00"},{walletId:"2",currencyCode:"USD",balance:"500.00"}]);
  if(p==="/api/transaction-history/accounts")return json(route,[{walletId:"1",currency:"EUR",status:"ACTIVE"},{walletId:"2",currency:"USD",status:"ACTIVE"}]);
  if(p==="/api/transaction-history"){
   const id=url.searchParams.get("walletId"),size=Number(url.searchParams.get("size")||20);calls.push({id,size});
   if(id==="1"&&failEuro)return json(route,{message:"EUR history unavailable"},503);
   const entries=id==="1"?rows("1","EUR"):id==="2"?(emptyDollar?[]:rows("2","USD")):rows("1","EUR");
   return json(route,{entries:entries.slice(0,size),total:entries.length,hasNext:entries.length>size});
  }
  return json(route,{},404);
 });
 await context.route("**/m3/**",route=>json(route,{},401));
 await page.goto("http://localhost:5195/dashboard");
 assert.equal(await page.locator(".wallet-recents").count(),0);
 await page.getByRole("link",{name:"Wallet Overview",exact:true}).click();await page.waitForURL("**/wallets");
 const euro=page.getByRole("table",{name:"Recent transactions for EUR wallet 1",exact:true}),dollar=page.getByRole("table",{name:"Recent transactions for USD wallet 2",exact:true});
 await euro.waitFor();await dollar.waitFor();assert.equal(await euro.locator("tbody tr").count(),5);assert.equal(await dollar.locator("tbody tr").count(),5);
 assert.equal(await euro.locator("tbody tr").first().locator("td").last().innerText(),"+8.00\nEUR");
 assert.ok(calls.some(c=>c.id==="1"&&c.size===5)&&calls.some(c=>c.id==="2"&&c.size===5));
 const placement=await page.locator(".wallet-recents").evaluateAll(nodes=>nodes.every(node=>node.nextElementSibling?.textContent.includes("View activity")));assert.equal(placement,true);
 assert.equal(await euro.locator("tbody").innerText().then(t=>t.includes("USD")),false);
 await page.screenshot({path:"verification/dashboard-wallet-recents.png",fullPage:true});
 await page.getByRole("link",{name:"Wallet Overview",exact:true}).click();await page.waitForURL("**/wallets");await euro.waitFor();await dollar.waitFor();
 assert.equal(await euro.locator("tbody tr").count(),5);assert.equal(await dollar.locator("tbody tr").count(),5);
 assert.equal(await page.getByRole("heading",{name:"Wallet Controls",exact:true}).count(),1);
 assert.equal(await page.locator(".wallet-overview-page").getByRole("button",{name:"Deposit money",exact:true}).count(),1);
 assert.equal(await page.locator(".wallet-overview-page").getByRole("button",{name:"Withdraw money",exact:true}).count(),1);
 assert.equal(await page.locator(".wallet-overview-page .wallet-card > a").first().evaluate(node=>getComputedStyle(node).fontSize),"19px");
 assert.equal(await page.locator(".wallet-overview-page .funding-actions").evaluate(node=>Boolean(node.compareDocumentPosition(document.querySelector(".wallet-overview-page .wallet-grid")) & Node.DOCUMENT_POSITION_FOLLOWING)),true);
 assert.equal(await page.locator(".wallet-overview-page").getByRole("link",{name:/exchange|conversion/i}).count(),0);
 assert.equal(await page.locator(".wallet-overview-page .wallet-recents").evaluateAll(nodes=>nodes.every(node=>node.nextElementSibling?.textContent.includes("View activity"))),true);
 await page.screenshot({path:"verification/wallet-overview-with-recents.png",fullPage:true});
 failEuro=true;await page.reload();await page.getByText("EUR history unavailable",{exact:true}).waitFor();await dollar.waitFor();assert.equal(await dollar.locator("tbody tr").count(),5);
 failEuro=false;await page.getByRole("button",{name:"Try again",exact:true}).click();await euro.waitFor();
 emptyDollar=true;await page.reload();await page.getByText("No transactions yet for this wallet.",{exact:true}).waitFor();await euro.waitFor();
 await page.setViewportSize({width:390,height:844});assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);await page.screenshot({path:"verification/dashboard-wallet-recents-mobile.png",fullPage:true});
 assert.deepEqual(errors,[]);console.log("Wallet Recents browser checks passed: five latest entries per wallet, placement above View activity, wallet separation, error/retry, empty history and mobile layout. No new page or live writes.");await context.close();
}finally{if(browser)await browser.close();await vite.close();}
