import test from "node:test";
import assert from "node:assert/strict";
import {createFundingClient} from "../src/features/dashboard/funding/fundingApi.js";
import {validFundingAmount,eligibleWallets,saveFundingIntent,readFundingIntent,recoveryKey,confirmFundingResult} from "../src/features/dashboard/funding/funding.js";
const intent={operation:"DEPOSIT",walletId:"1",amount:"2.00",currency:"EUR",key:"fixture-original-key"};
const profile={account:{customerRef:"customer-fixture"},csrfToken:"m3-csrf",wallets:[{walletId:"1",currency:"EUR",status:"ACTIVE",balance:"1000.00"}]};
const result={...intent,transactionRef:"funding-reference",idempotencyKey:intent.key,balanceAfter:"1002.0000"};
const response=(data,status=200)=>({ok:status>=200&&status<300,status,json:async()=>data});
test("funding amount validation rejects zero, exponents, negatives and extra decimals",()=>{
 for(const value of ["0","0.00","-1","1e3","1.001"," 1.00","1234567890123456"])assert.equal(validFundingAmount(value),false);
 assert.equal(validFundingAmount("0.01"),true);assert.equal(validFundingAmount("0002.00"),true);
 assert.deepEqual(eligibleWallets([{currency:"EUR",status:"ACTIVE"},{currency:"USD",status:"CLOSED"}]),[{currency:"EUR",status:"ACTIVE"}]);
});
test("recovery stores only immutable intent and never credentials",()=>{
 const entries=new Map(),storage={setItem:(k,v)=>entries.set(k,v),getItem:k=>entries.get(k),removeItem:k=>entries.delete(k)};
 saveFundingIntent("customer-fixture",{...intent,password:"never-save-password",csrfToken:"never-save-token"},storage);
 assert.doesNotMatch(entries.get(recoveryKey("customer-fixture")),/password|token|never-save/);
 assert.deepEqual(readFundingIntent("customer-fixture",storage),intent);
 assert.equal(readFundingIntent("another-customer",storage),null);
 saveFundingIntent("customer-fixture",null,storage);assert.equal(readFundingIntent("customer-fixture",storage),null);
});
test("funding POST preserves exact password, amount, original key and M3 CSRF",async()=>{
 let sent;
 const client=createFundingClient(async(url,options)=>{
  if(url==="/m3/api/dashboard")return response(profile);
  sent={url,options};return response(result);
 });
 assert.equal((await client.complete(intent," exact password ","customer-fixture")).transactionRef,"funding-reference");
 assert.equal(sent.url,"/m3/api/dashboard/funding");assert.equal(sent.options.headers["X-CSRF-TOKEN"],"m3-csrf");
 assert.equal(sent.options.headers["Idempotency-Key"],intent.key);assert.equal(sent.options.credentials,"same-origin");
 assert.deepEqual(JSON.parse(sent.options.body),{operation:"DEPOSIT",walletId:"1",amount:"2.00",password:" exact password "});
});
test("session recovery uses existing handoff and verifies the resulting customer",async()=>{
 let profiles=0,handOffs=0;
 const client=createFundingClient(async(url)=>{
  if(url==="/m3/dashboard/login"){handOffs++;return {ok:true,body:{cancel:async()=>{}}};}
  return ++profiles===1?response({code:"AUTHENTICATION_REQUIRED"},401):response(profile);
 });
 assert.equal((await client.session("customer-fixture")).account.customerRef,"customer-fixture");assert.equal(handOffs,1);
});
test("a mismatched M3 session cannot fund a different customer's wallet",async()=>{
 let posts=0;
 const client=createFundingClient(async(url,options)=>{
  if(options.method==="POST")posts++;
  if(url==="/m3/dashboard/login")return {ok:true,body:{cancel:async()=>{}}};
  return response({...profile,account:{customerRef:"someone-else"}});
 });
 await assert.rejects(client.complete(intent,"password","customer-fixture"),e=>e.code==="IDENTITY_MISMATCH");assert.equal(posts,0);
});
test("server rejection is distinct from uncertain network/server outcomes",async()=>{
 for(const status of [403,409,503]){
  const client=createFundingClient(async(url)=>url==="/m3/api/dashboard"?response(profile):response({code:status===409?"INSUFFICIENT_FUNDS":"REQUEST_FAILED"},status));
  await assert.rejects(client.complete(intent,"password","customer-fixture"),e=>e.status===status&&e.uncertain===(status>=500));
 }
});
test("recovery checks outcome without resubmitting a financial write",async()=>{
 const calls=[];const client=createFundingClient(async(url,options)=>{calls.push({url,method:options.method});return response(url==="/m3/api/dashboard"?profile:result);});
 await client.outcome(intent,"customer-fixture");assert.equal(calls[1].url,"/m3/api/dashboard/funding/"+intent.key);assert.ok(calls.every(c=>!c.method));
});
test("only a receipt matching the key, wallet, currency, operation and amount confirms success",()=>{
 for(const patch of [{idempotencyKey:"other"},{walletId:"2"},{amount:"3.00"},{currency:"USD"},{operation:"WITHDRAWAL"},{balanceAfter:null}])assert.throws(()=>confirmFundingResult({...result,...patch},intent),/INVALID_RESPONSE/);
 assert.equal(confirmFundingResult({...result,amount:"02.0"},intent).transactionRef,"funding-reference");
});
