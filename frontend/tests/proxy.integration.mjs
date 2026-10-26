// Exercises real HTTP redirects/cookies against disposable local fixtures only.
import assert from "node:assert/strict";
import http from "node:http";
import {createRequire} from "node:module";
import {createServer} from "vite";
import {backendProxy} from "../proxy-config.js";
const require=createRequire(import.meta.url);
const {request}=require(process.env.PLAYWRIGHT_MODULE || "playwright");
let m2Url,m3Url;
const send=(res,status,body,headers={})=>{res.writeHead(status,{"Content-Type":"text/html",...headers});res.end(body);};
const m2=http.createServer((req,res)=>{
 if(req.url==="/login" && req.method==="GET") return send(res,200,'<input name="_csrf" value="fixture-csrf">',{"Set-Cookie":"FXLOCK_M2_SESSION=guest; Path=/; HttpOnly; SameSite=Lax"});
 if(req.url==="/login" && req.method==="POST") return send(res,302,"",{"Set-Cookie":"FXLOCK_M2_SESSION=authenticated; Path=/; HttpOnly; SameSite=Lax",Location:m3Url+"/dashboard/login"});
 if(!req.headers.cookie?.includes("FXLOCK_M2_SESSION=authenticated")) return send(res,401,"Unauthorized");
 if(req.url?.startsWith("/dashboard/handoff")) return send(res,302,"",{Location:m3Url+"/dashboard/callback?state=fixture&code=one-use"});
 if(req.url==="/api/v1/wallets") return send(res,200,'[{"walletId":1}]',{"Content-Type":"application/json"});
 return send(res,404,"No fixture route");
});
const m3=http.createServer((req,res)=>{
 if(req.url==="/dashboard/login") return send(res,302,"",{"Set-Cookie":"FXLOCK_M3_SESSION=handoff; Path=/; HttpOnly; SameSite=Lax",Location:m2Url+"/dashboard/handoff?state=fixture"});
 if(req.url?.startsWith("/dashboard/callback") && req.headers.cookie?.includes("FXLOCK_M3_SESSION=handoff")) return send(res,302,"",{"Set-Cookie":"FXLOCK_M3_SESSION=authenticated; Path=/; HttpOnly; SameSite=Lax",Location:"/dashboard/"});
 if(req.url==="/dashboard/" && req.headers.cookie?.includes("FXLOCK_M3_SESSION=authenticated")) return send(res,200,"Dashboard session established");
 return send(res,401,"Unauthorized");
});
const listen=(server)=>new Promise(resolve=>server.listen(0,"127.0.0.1",resolve));
await listen(m2);await listen(m3);
m2Url="http://127.0.0.1:"+m2.address().port;m3Url="http://127.0.0.1:"+m3.address().port;
const vite=await createServer({configFile:false,server:{host:"127.0.0.1",port:5198,strictPort:true,open:false,proxy:backendProxy({QUOTE_BACKEND_URL:m2Url,SETTLEMENT_BACKEND_URL:m3Url})}});
let client;
try {
 await vite.listen();
 client=await request.newContext({baseURL:"http://localhost:5198"});
 await client.get("/m2/login");
 const response=await client.post("/m2/login",{form:{username:"fixture@example.com",password:"fixture-password",_csrf:"fixture-csrf"}});
 assert.equal(response.status(),200);assert.equal(new URL(response.url()).pathname,"/m3/dashboard/");
 assert.equal(await response.text(),"Dashboard session established");
 const wallet=await client.get("/m2/api/v1/wallets");assert.equal(wallet.status(),200);
 const cookies=(await client.storageState()).cookies;
 assert.equal(cookies.find(c=>c.name==="FXLOCK_M2_SESSION").value,"authenticated");
 assert.equal(cookies.find(c=>c.name==="FXLOCK_M3_SESSION").value,"authenticated");
 assert.ok(cookies.every(c=>c.httpOnly));
 console.log("Proxy integration passed: registered-login redirect chain, independent HttpOnly sessions, and authenticated wallet request. No live services used.");
} finally {
 if(client) await client.dispose();await vite.close();
 await Promise.all([new Promise(resolve=>m2.close(resolve)),new Promise(resolve=>m3.close(resolve))]);
}
