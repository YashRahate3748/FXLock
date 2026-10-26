import {useEffect,useState} from "react";
import {apiClient} from "../../../services/apiClient";
import {money,dateTime,isZero} from "../../../lib/format";
import {loadWalletRecents} from "../api/walletRecents";
import "./WalletRecents.css";
const types={DEPOSIT:"Deposit",WITHDRAWAL:"Withdrawal",FX_DEBIT:"FX debit",FX_CREDIT:"FX credit"};
export default function WalletRecents({walletId,currency,revision}) {
  const [loaded,setLoaded]=useState(null),[retry,setRetry]=useState(0);
  useEffect(()=>{
    let active=true;const request=new AbortController();
    loadWalletRecents(apiClient,walletId,request.signal)
      .then(entries=>{if(active)setLoaded({walletId,revision,retry,entries});})
      .catch(error=>{if(active)setLoaded({walletId,revision,retry,error:error.message});});
    return ()=>{active=false;request.abort();};
  },[walletId,revision,retry]);
  const loading=!loaded || loaded.walletId!==walletId || loaded.revision!==revision || loaded.retry!==retry;
  return <section className="wallet-recents" aria-label={currency+" wallet recents"}>
    <div className="wallet-recents-heading"><h3>Recents</h3><span>5 latest transactions</span></div>
    {loading?<p className="hint" role="status">Loading recent transactions…</p>:loaded.error?<div className="wallet-recents-error"><p role="alert">{loaded.error}</p><button type="button" className="text-button" onClick={()=>setRetry(value=>value+1)}>Try again</button></div>:loaded.entries.length===0?<p className="hint">No transactions yet for this wallet.</p>:<div className="wallet-recents-scroll" role="region" aria-label={currency+" recent transactions"} tabIndex={0}>
      <table aria-label={"Recent transactions for "+currency+" wallet "+walletId}><thead><tr><th>Date & time</th><th>Transaction</th><th>Amount</th></tr></thead><tbody>
        {loaded.entries.map(entry=>{const debit=!isZero(entry.amountDebited);return <tr key={entry.entryId||entry.ledgerId}>
          <td>{dateTime(entry.transactionDate)}</td><td title={entry.transactionRef||entry.settlementId||""}><strong>{types[entry.transactionType] || (debit?"Debit":"Credit")}</strong><small>{entry.customerName}</small></td>
          <td className={debit?"debit":"credit"}>{debit?"−":"+"}{money(debit?entry.amountDebited:entry.amountCredited)} <small>{entry.currency}</small></td>
        </tr>;})}
      </tbody></table>
    </div>}
  </section>;
}
