import test from "node:test";
import assert from "node:assert/strict";
import {parseBackendJson, expandDecimal, filterHistory, readHistory, receiptView} from "../src/services/compatibility.js";
import {rewriteLocation, backendProxy} from "../proxy-config.js";
test("Oracle decimal values and large IDs survive JSON without rounding", () => {
 const data = parseBackendJson('{"walletId":9007199254740993,"balance":123456789012345.6789,"sourceAmount":1.2300,"page":2,"message":"safe"}');
 assert.equal(data.walletId,"9007199254740993"); assert.equal(data.balance,"123456789012345.6789");
 assert.equal(data.sourceAmount,"1.2300"); assert.equal(data.page,2);
});
test("scientific-notation numbers expand without floating-point conversion", () => {
 assert.equal(expandDecimal("1.234567890123456789e+18"),"1234567890123456789");
 assert.equal(expandDecimal("-1.2e-4"),"-0.00012");
});
const rows = [
 {entryId:"FUNDING:1",transactionRef:"deposit-1",customerName:"Alice",currency:"EUR",transactionDate:"2026-10-25T23:59:59Z"},
 {entryId:"FX:2",settlementId:"transfer-2",customerName:"Alice",currency:"USD",transactionDate:"2026-10-26T00:00:00Z"},
 {entryId:"FX:3",settlementId:"transfer-3",customerName:"Bob",currency:"EUR",transactionDate:"2026-10-27T00:00:00Z"},
];
test("history filters are inclusive at start and exclusive at end", () => {
 assert.deepEqual(filterHistory(rows,{start:"2026-10-26T00:00:00Z",end:"2026-10-27T00:00:00Z"}).map(r=>r.entryId),["FX:2"]);
 assert.throws(()=>filterHistory(rows,{start:"2026-10-27",end:"2026-10-26"}),/start date/);
});
test("history search includes deposits, customer names and currency", () => {
 assert.equal(filterHistory(rows,{search:"DEPOSIT-1"}).length,1);
 assert.equal(filterHistory(rows,{search:"alice",currency:"USD"})[0].entryId,"FX:2");
});
test("extra filters read every backend page before pagination", async () => {
 const calls=[];
 const client={get:async (_path,{params})=>{ calls.push(params); return {data:{entries:params.page===0?[rows[0]]:[rows[1]],hasNext:params.page===0}}; }};
 const result=await readHistory(client,{search:"transfer-2",size:10});
 assert.equal(calls.length,2); assert.equal(calls[1].page,1); assert.equal(result.total,1); assert.equal(result.entries[0].entryId,"FX:2");
 assert.equal(calls[0].search,undefined);
});
test("plain pagination stays on the backend and carries wallet ownership filter", async () => {
 let sent;
 const result=await readHistory({get:async (_,{params})=>{sent=params;return {data:{entries:[],total:0}};}},{walletId:"123",page:2,size:10});
 assert.deepEqual(sent,{walletId:"123",page:2,size:10}); assert.equal(result.total,0);
});
test("incomplete history errors rather than presenting partial results", async () => {
 await assert.rejects(readHistory({get:async()=>({data:{entries:[],hasNext:true}})},{search:"x"}),/too large/);
});
test("existing receipt DTO maps both directions and retains allowed ledger rows", () => {
 const receipt=receiptView({sourceCurrency:"USD",targetCurrency:"EUR",sourceAmount:"5.00",targetAmount:"4.20",lockedCustomerRate:"0.84",status:"COMPLETED",ledgerEntries:[{entryType:"DEBIT",walletId:"2"},{entryType:"CREDIT",walletId:"3"}]});
 assert.equal(receipt.SOURCE_CURRENCY,"USD"); assert.equal(receipt.CUSTOMER_RATE,"0.84"); assert.equal(receipt.TARGET_WALLET_ID,"3");
 assert.equal(receipt.ledgerEntries.length,2);
});
const targets={"/m2":"http://127.0.0.1:8082","/m3":"http://127.0.0.1:8083"};
test("registered login handoff redirects remain on the frontend origin",()=>{
 assert.equal(rewriteLocation("http://localhost:8083/dashboard/login","/m2",targets["/m2"],targets),"/m3/dashboard/login");
 assert.equal(rewriteLocation("/dashboard/callback?state=x&code=y","/m3",targets["/m3"],targets),"/m3/dashboard/callback?state=x&code=y");
 assert.equal(rewriteLocation("http://localhost:8082/login?error","/m3",targets["/m3"],targets),"/m2/login?error");
 assert.equal(rewriteLocation("https://example.com/other","/m2",targets["/m2"],targets),"https://example.com/other");
});
test("development proxy keeps analyst API separate from customer APIs",()=>{
 const proxy=backendProxy(); assert.equal(proxy["/api/v1"].target,"http://127.0.0.1:8081");
 assert.equal(proxy["/m2"].rewrite("/m2/api/v1/wallets"),"/api/v1/wallets");
 assert.equal(proxy["/m3"].rewrite("/m3/api/dashboard"),"/api/dashboard");
});
