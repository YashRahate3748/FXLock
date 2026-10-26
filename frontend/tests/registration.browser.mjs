// All registration POSTs are intercepted; no live customer is created.
import assert from "node:assert/strict";
import {createRequire} from "node:module";
import {createServer} from "vite";
const require=createRequire(import.meta.url);const {chromium}=require(process.env.PLAYWRIGHT_MODULE||"playwright");
const login='<html><body><form><input name="_csrf" value="login-token"></form></body></html>';
const registration='<html><body><form method="post" action="/register"><input name="fullName"><input name="email"><input name="password"><input name="confirmPassword"><input name="_csrf" value="register-token"></form></body></html>';
const server=await createServer({plugins:[{name:"test-registration-redirect",configureServer(vite){vite.middlewares.use((req,res,next)=>{if(req.url==="/m2/login?registered"){res.writeHead(200,{"Content-Type":"text/html"});res.end(login);}else next();});}}],server:{port:5196,strictPort:true,open:false}});await server.listen();let browser;
try {
 browser=await chromium.launch({headless:true,channel:"chrome"});const context=await browser.newContext({viewport:{width:1440,height:1100}}),page=await context.newPage();page.setDefaultTimeout(10000);
 let posts=[],networkFailure=false;const errors=[];page.on("pageerror",error=>errors.push(error.message));
 await context.route("**/m2/**",async route=>{
  const request=route.request(),url=new URL(request.url());
  if(url.pathname==="/m2/register" && request.method()==="GET")return route.fulfill({contentType:"text/html",body:registration});
  if(url.pathname==="/m2/register" && request.method()==="POST"){
   const data=new URLSearchParams(request.postData());posts.push(Object.fromEntries(data));assert.equal(data.get("_csrf"),"register-token");
   if(networkFailure)return route.abort("failed");
   if(data.get("email")==="existing@example.com")return route.fulfill({contentType:"text/html",body:'<html><div class="notice" role="alert"><ul><li>An account already exists for this email. Sign in instead.</li></ul></div></html>'});
   return route.fulfill({status:302,headers:{Location:"/m2/login?registered"}});
  }
  return route.fulfill({contentType:"text/html",body:login});
 });
 await page.goto("http://localhost:5196/login");
 const signup=page.getByRole("link",{name:"Create account",exact:true});await signup.waitFor();
 assert.equal(await page.getByRole("button",{name:"Sign in",exact:true}).evaluate(node=>node.nextElementSibling?.textContent.trim()),"Create account");
 await signup.click();await page.waitForURL("**/register");
 await page.getByRole("heading",{name:/FXLock account/}).waitFor();await page.screenshot({path:"verification/registration-desktop.png",fullPage:true});
 const fill=async(email,password,confirmation=password)=>{
  await page.getByLabel("Full name",{exact:true}).fill(" Fixture Customer ");await page.getByLabel("Email",{exact:true}).fill(email);
  await page.getByLabel("Password",{exact:true}).fill(password);await page.getByLabel("Confirm password",{exact:true}).fill(confirmation);
 };
 const submit=()=>page.getByRole("button",{name:"Create account",exact:true}).click();
 await fill("fixture@example.com","demo-password","different");await submit();await page.getByText("Passwords must match.",{exact:true}).waitFor();assert.equal(posts.length,0);
 assert.equal(await page.getByLabel("Password",{exact:true}).inputValue(),"");assert.equal(await page.getByLabel("Confirm password",{exact:true}).inputValue(),"");
 await fill("fixture@example.com","😀".repeat(19));await submit();await page.getByText(/nonblank password of at most 72 UTF-8 bytes/).waitFor();assert.equal(posts.length,0);
 await fill("existing@example.com","demo-password");await submit();await page.getByText("An account already exists for this email. Sign in instead.",{exact:true}).waitFor();assert.equal(posts.length,1);
 assert.equal(await page.getByLabel("Password",{exact:true}).inputValue(),"");
 networkFailure=true;await fill("fixture@example.com","demo-password");await submit();await page.getByText(/Check your connection and try signing in before submitting again/).waitFor();assert.equal(posts.length,2);networkFailure=false;
 await fill("FIXTURE@example.com"," exact fixture password ");
 await page.locator("form").evaluate(form=>{form.dispatchEvent(new Event("submit",{bubbles:true,cancelable:true}));form.dispatchEvent(new Event("submit",{bubbles:true,cancelable:true}));});
 await page.waitForURL("**/login");await page.getByText("Account created. Sign in with your email and password.",{exact:true}).waitFor();assert.equal(posts.length,3);
 assert.equal(posts[2].fullName,"Fixture Customer");assert.equal(posts[2].email,"fixture@example.com");assert.equal(posts[2].password," exact fixture password ");assert.equal(posts[2].confirmPassword," exact fixture password ");
 assert.equal(await page.getByLabel("Email",{exact:true}).inputValue(),"fixture@example.com");assert.equal(await page.getByLabel("Password",{exact:true}).inputValue(),"");
 const stored=await page.evaluate(()=>JSON.stringify({local:{...localStorage},session:{...sessionStorage},history:history.state}));assert.ok(!stored.includes("fixture password")&&!stored.includes("register-token"));
 await page.getByRole("link",{name:"Create account",exact:true}).click();await page.setViewportSize({width:390,height:844});
 assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);await page.screenshot({path:"verification/registration-mobile.png",fullPage:true});
 assert.deepEqual(errors,[]);console.log("Registration browser checks passed: button placement, public page, matching passwords, UTF-8 limit, duplicate email, lost response without retry, CSRF, exact password preservation, double-submit protection, confirmed success, login email prefill and mobile layout. No live accounts created.");
 await context.close();
}finally{if(browser)await browser.close();await server.close();}
