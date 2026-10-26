import test from "node:test";
import assert from "node:assert/strict";
import {loadWalletRecents} from "../src/features/dashboard/api/walletRecents.js";
test("wallet Recents requests only the selected wallet's newest five entries",async()=>{
 let sent;const signal=new AbortController().signal;const entries=Array.from({length:7},(_,i)=>({walletId:"123",entryId:String(i)}));
 const result=await loadWalletRecents({get:async(path,options)=>{sent={path,options};return {data:{entries}};}},"123",signal);
 assert.equal(sent.path,"/api/transaction-history");assert.deepEqual(sent.options.params,{walletId:"123",page:0,size:5});assert.equal(sent.options.signal,signal);assert.equal(result.length,5);assert.equal(result[0].entryId,"0");
});
test("wallet Recents rejects another wallet's entries instead of displaying them",async()=>{
 await assert.rejects(loadWalletRecents({get:async()=>({data:{entries:[{walletId:"other"}]}})},"123"),/could not be verified/);
});
test("empty wallet history stays empty",async()=>{assert.deepEqual(await loadWalletRecents({get:async()=>({data:{entries:[]}})},"123"),[]);});
