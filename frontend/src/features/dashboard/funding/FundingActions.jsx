import {useState} from "react";
import {useSearchParams} from "react-router-dom";
import {ArrowDownLeft,ArrowUpRight,CheckCircle2,RefreshCw} from "lucide-react";
import Button from "../../../components/ui/Button";
import Card from "../../../components/ui/Card";
import {money} from "../../../lib/format";
import {eligibleWallets,readFundingIntent,saveFundingIntent} from "./funding";
import FundingDialog from "./FundingDialog";
import "./funding.css";
export default function FundingActions({customerRef,wallets,onCompleted,showControls=true}) {
  const [params,setParams]=useSearchParams();
  const [localOperation,setOperation]=useState(null),[pending,setPending]=useState(()=>readFundingIntent(customerRef)),[result,setResult]=useState(null),[refreshError,setRefreshError]=useState("");
  const requestedOperation=params.get("funding")==="deposit"?"DEPOSIT":params.get("funding")==="withdraw"?"WITHDRAWAL":null;
  const operation=localOperation || (requestedOperation && !refreshError ? pending?.operation || requestedOperation : null);
  function closeDialog(){setOperation(null);if(params.has("funding")){const next=new URLSearchParams(params);next.delete("funding");setParams(next,{replace:true});}}
  function remember(intent){saveFundingIntent(customerRef,intent);setPending(intent);}
  async function completed(next){
    // A confirmed operation stays confirmed even if refreshing the page data fails.
    setResult(next);setRefreshError("");
    try {remember(null);} catch {setPending(null);setRefreshError("Completed. Recovery storage could not be cleared; keep the transaction reference.");}
    try {await onCompleted();} catch {setRefreshError("Completed, but the latest balances could not be refreshed. Refresh the dashboard before another request.");}
  }
  if(!showControls && !pending && !result && !refreshError && !operation)return null;
  return <Card className={showControls?"funding-actions":"funding-actions funding-feedback"} aria-labelledby={showControls?"wallet-controls-title":undefined} aria-label={showControls?undefined:"Wallet funding status"}>
    {showControls && <><div className="section-heading"><h2 id="wallet-controls-title">Wallet Controls</h2></div>
    <p className="funding-description muted">Deposit adds simulated funds to your wallet; withdraw removes funds from your available balance.</p>
    <div className="funding-buttons"><Button type="button" disabled={Boolean(pending) || Boolean(refreshError) || !eligibleWallets(wallets).length} onClick={()=>setOperation("DEPOSIT")}><ArrowDownLeft size={17}/> Deposit money</Button>
    <Button type="button" variant="secondary" disabled={Boolean(pending) || Boolean(refreshError) || !eligibleWallets(wallets).length} onClick={()=>setOperation("WITHDRAWAL")}><ArrowUpRight size={17}/> Withdraw money</Button></div></>}
    {pending && <div className="funding-recovery"><p>A wallet funding request needs an outcome check.</p><Button type="button" variant="secondary" onClick={()=>setOperation(pending.operation)}><RefreshCw size={15}/> Recover funding request</Button></div>}
    {result && <div className="funding-success" role="status"><CheckCircle2 size={20}/><div><strong>{result.operation==="DEPOSIT"?"Deposit":"Withdrawal"} completed · {money(result.amount)} {result.currency}</strong><p>Wallet {result.walletId} balance: {money(result.balanceAfter)} {result.currency}</p><small>Transaction reference: {result.transactionRef}</small></div></div>}
    {refreshError && <p role="alert" className="inline-warning">{refreshError}</p>}
    {operation && <FundingDialog key={operation} operation={operation} customerRef={customerRef} wallets={wallets} pending={pending} remember={remember} completed={completed} close={closeDialog}/>}
  </Card>;
}
