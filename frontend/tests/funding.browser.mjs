// Disposable browser fixtures only: never sends a funding write to the live backend.
import assert from "node:assert/strict";
import {createRequire} from "node:module";
import {createServer} from "vite";
const require=createRequire(import.meta.url);const {chromium}=require(process.env.PLAYWRIGHT_MODULE||"playwright");
const vite=await createServer({server:{port:5199,strictPort:true,open:false}});await vite.listen();let browser;
try {
 browser=await chromium.launch({headless:true,channel:"chrome"});
 const context=await browser.newContext({viewport:{width:1440,height:1100}}),page=await context.newPage();page.setDefaultTimeout(10000);
 const errors=[];page.on("pageerror",e=>errors.push(e.message));
 let signedOut=false;
 let cents=100000,mode="normal",posts=[],ledger=[],receipts=new Map(),lastIntent;
 const balance=()=> (cents/100).toFixed(2);
 const wallets=()=>[{walletId:"1",currency:"EUR",status:"ACTIVE",balance:balance()},{walletId:"2",currency:"USD",status:"ACTIVE",balance:"500.00"}];
 const profile=()=>({account:{customerRef:"customer-fixture",fullName:"Fixture Customer"},csrfToken:"funding-csrf",wallets:wallets()});
 const json=(route,data,status=200)=>route.fulfill({status,contentType:"application/json",body:JSON.stringify(data)});
 const home='<html><head><meta name="csrf-token" content="quote-csrf"><meta name="csrf-header" content="X-CSRF-TOKEN"></head><body><header><span>Currency quotes · <span>customer-fixture</span></span></header><form id="quote-form"><input id="amount" min="1.00" max="10000.00"></form><p class="hint">Configured spread: <span>20</span></p></body></html>';
 await context.route("**/m2/**",async route=>{
  const req=route.request(),url=new URL(req.url()),p=url.pathname.slice(3);
  if(p==="/logout" && req.method()==="POST"){signedOut=true;return route.fulfill({contentType:"text/html",body:"<html>Signed out</html>"});}
  if(signedOut)return json(route,{code:"AUTHENTICATION_REQUIRED"},401);
  assert.equal(req.method(),"GET");
  if(p==="/")return route.fulfill({contentType:"text/html",body:home});
  if(p==="/api/v1/wallets")return json(route,wallets().map(w=>({walletId:w.walletId,currencyCode:w.currency,balance:w.balance})));
  if(p==="/api/transaction-history/accounts")return json(route,wallets());
  if(p==="/api/transaction-history")return json(route,{entries:ledger.slice(0,Number(url.searchParams.get("size")||20)),total:ledger.length,hasNext:false});
  return json(route,{},404);
 });
 function commit(intent,key){
  const change=Math.round(Number(intent.amount)*100);cents+=intent.operation==="DEPOSIT"?change:-change;
  const result={transactionRef:"funding-"+(receipts.size+1),operation:intent.operation,walletId:intent.walletId,currency:"EUR",amount:intent.amount,balanceAfter:balance(),idempotencyKey:key};receipts.set(key,result);
  ledger.unshift({entryId:result.transactionRef,transactionType:intent.operation,transactionRef:result.transactionRef,settlementId:null,walletId:"1",customerName:"Fixture Customer",currency:"EUR",amountCredited:intent.operation==="DEPOSIT"?intent.amount:"0.00",amountDebited:intent.operation==="WITHDRAWAL"?intent.amount:"0.00",updatedBalance:balance(),transactionDate:new Date().toISOString()});return result;
 }
 await context.route("**/m3/**",async route=>{
  const req=route.request(),p=new URL(req.url()).pathname.slice(3);
  if(p==="/api/dashboard")return json(route,profile());
  if(p==="/api/dashboard/logout")return json(route,{logoutUrl:"/m2/dashboard/logout"});
  if(p==="/api/dashboard/funding" && req.method()==="POST"){
   const body=req.postDataJSON(),key=req.headers()["idempotency-key"];posts.push({body,key});lastIntent={...body,key};
   assert.equal(req.headers()["x-csrf-token"],"funding-csrf");
   if(body.password!==" exact fixture password ")return json(route,{code:"CONFIRMATION_REJECTED"},403);
   if(receipts.has(key))return json(route,receipts.get(key));
   if(body.operation==="WITHDRAWAL" && Number(body.amount)*100>cents)return json(route,{code:"INSUFFICIENT_FUNDS"},409);
   if(mode==="lost-after-commit"){mode="normal";commit(body,key);return json(route,{code:"CONNECTION_UNCERTAIN"},503);}
   if(mode==="lost-before-commit"){mode="normal";return json(route,{code:"FUNDING_PENDING"},503);}
   return json(route,commit(body,key));
  }
  if(p.startsWith("/api/dashboard/funding/")){const key=p.split("/").at(-1);return json(route,receipts.get(key)||{code:"OUTCOME_NOT_FOUND"},receipts.has(key)?200:404);}
  return json(route,{},404);
 });
 const open=async operation=>{await page.getByRole("button",{name:operation==="DEPOSIT"?"Deposit money":"Withdraw money",exact:true}).click();await page.getByRole("dialog").waitFor();await page.getByLabel("Current login password",{exact:true}).waitFor({state:"visible"});};
 const submit=async(amount,password=" exact fixture password ")=>{await page.getByLabel(/^Amount/).fill(amount);await page.getByLabel("Current login password",{exact:true}).fill(password);await page.getByRole("button",{name:/Complete (deposit|withdrawal)/}).click();};
 await page.goto("http://localhost:5199/dashboard");await page.getByRole("heading",{name:/Good to see you/}).waitFor();
 const account=page.getByRole("button",{name:"Account menu",exact:true});
 await account.click();await page.getByRole("menu",{name:"Account actions"}).waitFor();
 assert.equal(await page.getByRole("menuitem").count(),4);
 await page.keyboard.press("Escape");assert.equal(await account.evaluate(node=>node===document.activeElement),true);
 await account.press("ArrowDown");await page.getByRole("menuitem",{name:"Deposit money",exact:true}).waitFor();
 await page.keyboard.press("ArrowDown");await page.keyboard.press("ArrowDown");assert.equal(await page.getByRole("menuitem",{name:"Withdraw money",exact:true}).evaluate(node=>node===document.activeElement),true);
 await page.keyboard.press("End");assert.equal(await page.getByRole("menuitem",{name:"Sign out",exact:true}).evaluate(node=>node===document.activeElement),true);
 await page.keyboard.press("Escape");await account.click();await page.getByRole("heading",{name:/Good to see you/}).click();assert.equal(await page.getByRole("menu").count(),0);
 await account.click();await page.screenshot({path:"verification/account-dropdown.png",fullPage:true});
 await page.getByRole("menuitem",{name:"Deposit money",exact:true}).click();await page.getByRole("dialog").waitFor();
 assert.equal(new URL(page.url()).searchParams.get("funding"),"deposit");await page.getByRole("heading",{name:"Deposit money",exact:true}).waitFor();
 await page.getByRole("button",{name:"Cancel",exact:true}).click();assert.equal(new URL(page.url()).searchParams.has("funding"),false);
 await page.goto("http://localhost:5199/transactions");await page.getByRole("heading",{name:"Your transaction history.",exact:true}).waitFor();
 await account.click();await page.getByRole("menuitem",{name:"Withdraw money",exact:true}).click();await page.getByRole("heading",{name:"Withdraw money",exact:true}).waitFor();
 assert.equal(new URL(page.url()).pathname,"/dashboard");await page.getByRole("button",{name:"Cancel",exact:true}).click();assert.equal(posts.length,0);
 await page.getByRole("link",{name:"Wallet Overview",exact:true}).click();await page.waitForURL("**/wallets");await page.getByRole("heading",{name:"Wallet Overview",exact:true}).waitFor();
 const usdWallet=page.getByRole("region",{name:"US Dollar",exact:true});await usdWallet.waitFor();
 assert.equal(await page.locator(".wallet-overview-page").getByRole("link",{name:/exchange|conversion/i}).count(),0);
 assert.equal(await page.locator(".wallet-overview-page .wallet-recents").count(),2);
 await page.getByRole("button",{name:"Deposit money",exact:true}).click();await page.getByRole("dialog").waitFor();await page.getByLabel("Wallet",{exact:true}).selectOption("2");
 assert.equal(await page.getByLabel("Wallet",{exact:true}).inputValue(),"2");await page.getByRole("button",{name:"Cancel",exact:true}).click();
 await usdWallet.getByRole("link",{name:"View activity",exact:true}).click();await page.waitForURL("**/transactions?walletId=2");
 await page.getByRole("link",{name:"Wallet Overview",exact:true}).click();await page.getByRole("heading",{name:"Wallet Overview",exact:true}).waitFor();
 await page.screenshot({path:"verification/wallet-overview.png",fullPage:true});
 await account.click();await page.getByRole("menuitem",{name:"Dashboard",exact:true}).click();await page.waitForURL("**/dashboard");
 assert.equal(posts.length,0);
 await open("DEPOSIT");await submit("0.00");await page.getByText("Enter a positive amount with at most two decimal places.",{exact:true}).waitFor();assert.equal(posts.length,0);
 await submit("10.25","wrong");await page.getByText("The current login password is incorrect or missing. Enter it again.",{exact:true}).waitFor();assert.equal(cents,100000);
 await submit("10.25");await page.getByRole("dialog").waitFor({state:"hidden"});await page.locator(".wallet-balance").filter({hasText:"1,010.25"}).waitFor();
 await open("WITHDRAWAL");await submit("999999.00");await page.getByText("This wallet has insufficient funds. No withdrawal was made.",{exact:true}).waitFor();assert.equal(cents,101025);
 await submit("1.25");await page.getByRole("dialog").waitFor({state:"hidden"});await page.locator(".wallet-balance").filter({hasText:"1,009.00"}).waitFor();
 mode="lost-after-commit";await open("DEPOSIT");await submit("2.00");await page.getByText(/The outcome is uncertain/).waitFor();
 const lostKey=lastIntent.key,before=posts.length;
 assert.equal(await page.getByLabel("Current login password",{exact:true}).inputValue(),"");
 const stored=await page.evaluate(()=>JSON.stringify({...sessionStorage}));assert.ok(!stored.includes("fixture password")&&!stored.includes("password")&&!stored.includes("csrf"));
 await page.getByRole("button",{name:"Cancel",exact:true}).click();await page.reload();await page.getByRole("button",{name:"Recover funding request",exact:true}).click();
 assert.equal(await page.getByLabel(/^Amount/).isDisabled(),true);
 await page.getByRole("button",{name:"Check outcome",exact:true}).click();await page.getByRole("dialog").waitFor({state:"hidden"});assert.equal(posts.length,before);assert.equal(receipts.get(lostKey).amount,"2.00");
 await page.locator(".wallet-balance").filter({hasText:"1,011.00"}).waitFor();
 mode="lost-before-commit";await open("WITHDRAWAL");await submit("0.50");await page.getByText(/The outcome is uncertain/).waitFor();const retryKey=lastIntent.key;
 await page.getByRole("button",{name:"Check outcome",exact:true}).click();await page.getByText(/No committed result is visible yet/).waitFor();
 await page.getByLabel("Current login password",{exact:true}).fill("wrong");await page.getByRole("button",{name:"Complete withdrawal",exact:true}).click();await page.getByText("The current login password is incorrect or missing. Enter it again.",{exact:true}).waitFor();
 assert.equal(await page.getByLabel(/^Amount/).isDisabled(),true);
 await page.getByLabel("Current login password",{exact:true}).fill(" exact fixture password ");await page.getByRole("button",{name:"Complete withdrawal",exact:true}).click();await page.getByRole("dialog").waitFor({state:"hidden"});
 assert.equal(lastIntent.key,retryKey);await page.locator(".wallet-balance").filter({hasText:"1,010.50"}).waitFor();
 await page.screenshot({path:"verification/dashboard-with-funding.png",fullPage:true});
 await open("DEPOSIT");await page.screenshot({path:"verification/deposit-dialog.png",fullPage:true});
 await page.setViewportSize({width:390,height:844});assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);
 await page.screenshot({path:"verification/deposit-dialog-mobile.png",fullPage:true});
 await page.getByRole("button",{name:"Cancel",exact:true}).click();
 await account.click();await page.getByRole("menuitem",{name:"Sign out",exact:true}).click();await page.waitForURL("**/login");assert.equal(signedOut,true);
 assert.deepEqual(errors,[]);
 console.log("Profile menu and funding browser checks passed: menu keyboard/outside click, deposit/withdraw navigation, shared sign-out, invalid amount, incorrect password, deposit, insufficient funds, withdrawal, exact password and CSRF, refresh, response-loss recovery after reload, same-key retry, password-free storage, desktop and mobile dialog. All transactions used disposable fixtures.");
 await context.close();
}finally{if(browser)await browser.close();await vite.close();}
