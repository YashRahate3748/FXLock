import {useEffect,useRef,useState} from "react";
import {ArrowDownLeft,ArrowUpRight,X,ShieldCheck} from "lucide-react";
import Button from "../../../components/ui/Button";
import ErrorMessage from "../../../components/feedback/ErrorMessage";
import {money} from "../../../lib/format";
import {fundingApi} from "./fundingApi";
import {eligibleWallets,fundingMessage,validFundingAmount} from "./funding";
export default function FundingDialog({operation,customerRef,wallets,pending,remember,completed,close}) {
  const dialog=useRef(null),password=useRef(null),walletSelect=useRef(null),inFlight=useRef(false);
  const [profile,setProfile]=useState(null),[loading,setLoading]=useState(true),[error,setError]=useState("");
  const [walletId,setWalletId]=useState(pending?.walletId || eligibleWallets(wallets)[0]?.walletId || ""),[amount,setAmount]=useState(pending?.amount || ""),[busy,setBusy]=useState(false);
  const active=eligibleWallets(profile?.wallets || wallets),selected=(profile?.wallets || wallets).find(w=>w.walletId===walletId);
  const title=operation==="DEPOSIT"?"Deposit money":"Withdraw money";
  useEffect(()=>{
    const node=dialog.current,opener=document.activeElement;
    node.showModal();walletSelect.current?.focus();
    return ()=>{node.close();opener?.focus();};
  },[]);
  useEffect(()=>{
    let live=true;
    fundingApi.session(customerRef).then(data=>{if(live)setProfile(data);}).catch(failure=>{if(live)setError(fundingMessage(failure));}).finally(()=>{if(live)setLoading(false);});
    return ()=>{live=false;};
  },[customerRef]);
  function cancel() {if(inFlight.current)return;if(password.current)password.current.value="";close();}
  async function submit(event) {
    event.preventDefault();if(inFlight.current || !profile)return;
    if(!validFundingAmount(amount)){setError("Enter a positive amount with at most two decimal places.");password.current.value="";return;}
    const wasRecovering=Boolean(pending);
    const intent=pending || {operation,walletId,amount,currency:selected?.currency,key:crypto.randomUUID()};
    try {remember(intent);} catch {setError("Recovery storage is unavailable. Enable session storage before submitting. No request was sent.");password.current.value="";return;}
    inFlight.current=true;setBusy(true);setError("");
    const confirmation=password.current.value;password.current.value="";
    try {const result=await fundingApi.complete(intent,confirmation,customerRef);await completed(result);close();}
    catch(failure) {setError(fundingMessage(failure));if(!failure.uncertain && !wasRecovering)remember(null);}
    finally {inFlight.current=false;setBusy(false);if(password.current)password.current.value="";}
  }
  async function check() {
    if(inFlight.current || !pending)return;
    inFlight.current=true;setBusy(true);setError("");password.current.value="";
    try {const result=await fundingApi.outcome(pending,customerRef);await completed(result);close();}
    catch(failure){setError(failure.status===404?"No committed result is visible yet. The request may still be processing. Recheck, or re-enter your password to retry the original request.":fundingMessage(failure));}
    finally{inFlight.current=false;setBusy(false);}
  }
  return <dialog ref={dialog} className="funding-dialog" aria-labelledby="funding-title" aria-describedby="funding-scope" onCancel={event=>{event.preventDefault();cancel();}}>
    <form onSubmit={submit}>
      <div className="funding-dialog-heading"><span className="funding-symbol">{operation==="DEPOSIT"?<ArrowDownLeft/>:<ArrowUpRight/>}</span><button className="icon-button" type="button" aria-label="Close funding dialog" disabled={busy} onClick={cancel}><X size={20}/></button></div>
      <p className="eyebrow">YOUR CURRENCY WALLET</p><h2 id="funding-title">{title}</h2>
      <p id="funding-scope" className="muted">Add or remove simulated funds from the selected wallet. No bank or card is connected.</p>
      {loading && <p role="status" className="hint">Checking your wallet session…</p>}
      <div className="field"><label htmlFor="funding-wallet">Wallet</label><select ref={walletSelect} id="funding-wallet" value={walletId} onChange={event=>setWalletId(event.target.value)} disabled={loading || busy || Boolean(pending)} required>
        {!active.length && <option value="">No eligible active wallets</option>}
        {active.map(wallet=><option key={wallet.walletId} value={wallet.walletId}>{wallet.currency} wallet · {wallet.walletId} · Balance {money(wallet.balance)}</option>)}
        {pending && !active.some(w=>w.walletId===pending.walletId) && <option value={pending.walletId}>Previously selected wallet · {pending.walletId}</option>}
      </select></div>
      <div className="field"><label htmlFor="funding-amount">Amount {selected?.currency || pending?.currency || ""}</label><input id="funding-amount" inputMode="decimal" autoComplete="off" value={amount} onChange={event=>setAmount(event.target.value)} required disabled={loading || busy || Boolean(pending)} aria-describedby="funding-amount-hint"/><p id="funding-amount-hint" className="hint">Use at most two decimal places. No currency conversion.</p></div>
      <div className="field"><label htmlFor="funding-password">Current login password</label><input id="funding-password" ref={password} type="password" autoComplete="off" required disabled={loading || busy}/></div>
      {pending && <p className="funding-recovery hint">The original wallet, amount and request key are retained until the outcome is confirmed.</p>}
      <ErrorMessage message={error}/>
      {busy && <p role="status" className="hint">Processing. A submitted transaction cannot be cancelled.</p>}
      <div className="funding-dialog-actions"><Button type="submit" busy={busy} disabled={loading || !profile || (!active.length && !pending)}>Complete {operation==="DEPOSIT"?"deposit":"withdrawal"}</Button><Button type="button" variant="secondary" disabled={busy} onClick={cancel}>Cancel</Button>
      {pending && <Button type="button" variant="text" disabled={busy || loading} onClick={check}>Check outcome</Button>}</div>
      <p className="form-assurance"><ShieldCheck size={15}/> Your password is used for confirmation and is never saved by this form.</p>
    </form>
  </dialog>;
}
