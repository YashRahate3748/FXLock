import {useEffect,useState} from "react";
import {Link,useSearchParams} from "react-router-dom";
import {Wallet,ArrowUpRight,RefreshCw} from "lucide-react";
import useSession from "../../../hooks/useSession";
import Card from "../../../components/ui/Card";
import Button from "../../../components/ui/Button";
import StatusBadge from "../../../components/ui/StatusBadge";
import ErrorMessage from "../../../components/feedback/ErrorMessage";
import WalletRecents from "../../dashboard/components/WalletRecents";
import FundingActions from "../../dashboard/funding/FundingActions";
import {money} from "../../../lib/format";
import "./wallets.css";
export default function WalletsPage(){
  const {session,refresh}=useSession(),[params]=useSearchParams();
  const [error,setError]=useState(""),[loading,setLoading]=useState(true);
  useEffect(()=>{let active=true;refresh().catch(()=>{if(active)setError("Latest balances could not be refreshed. Please try again.");}).finally(()=>{if(active)setLoading(false);});return()=>{active=false;};},[refresh]);
  async function reload(){setLoading(true);setError("");try{await refresh();}catch{setError("Latest balances could not be refreshed. Please try again.");}finally{setLoading(false);}}
  const requestedWallet=params.get("walletId"),selected=session.wallets.find(wallet=>wallet.walletId===requestedWallet);
  const invalidSelection=Boolean(requestedWallet&&!selected);
  return <div className="page-enter wallet-overview-page">
    <div className="page-heading"><div><p className="eyebrow">YOUR WALLETS, IN ONE PLACE</p><h1>Wallet Overview</h1><p>Manage your wallet balances and review activity. EUR and USD remain separate.</p></div><Button variant="secondary" busy={loading} onClick={reload}><RefreshCw size={16}/>Refresh balances</Button></div>
    <ErrorMessage message={error || (invalidSelection?"Choose one of your own wallets to manage its funds.":"")}/>
    {!invalidSelection&&<FundingActions key={session.username} customerRef={session.username} wallets={session.wallets} onCompleted={refresh}/>}
    <div className="wallet-grid">
      {session.wallets.map(wallet=><Card key={wallet.walletId} className="wallet-card" aria-labelledby={"wallet-heading-"+wallet.walletId}>
        <div className="wallet-top"><span className={"currency-icon "+wallet.currency.toLowerCase()}>{wallet.currency==="EUR"?"€":"$"}</span><span><h2 id={"wallet-heading-"+wallet.walletId}>{wallet.currency==="EUR"?"Euro":"US Dollar"}</h2><small>{wallet.currency} wallet · {wallet.walletId}</small></span><StatusBadge status={wallet.status}/></div>
        <p className="small-label">AVAILABLE BALANCE</p><div className="wallet-balance">{money(wallet.balance)} <span>{wallet.currency}</span></div>
        <dl className="wallet-overview-details"><div><dt>Wallet ID</dt><dd>{wallet.walletId}</dd></div><div><dt>Currency</dt><dd>{wallet.currency}</dd></div><div><dt>Status</dt><dd>{wallet.status}</dd></div></dl>
        <WalletRecents walletId={wallet.walletId} currency={wallet.currency} revision={wallet.balance}/>
        <Link to={"/transactions?walletId="+encodeURIComponent(wallet.walletId)}>View activity<ArrowUpRight size={17}/></Link>
      </Card>)}
      {session.wallets.length===0&&<Card><Wallet/><h2>No wallets yet</h2><p>No wallets are available for this account.</p></Card>}
    </div>
  </div>;
}
