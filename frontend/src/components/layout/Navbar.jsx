import {useEffect,useRef,useState} from "react";
import {Menu,ChevronDown,ArrowDownLeft,ArrowUpRight,LogOut,LayoutDashboard} from "lucide-react";
import {Link,useLocation} from "react-router-dom";
import useSession from "../../hooks/useSession";
import "./Navbar.css";
const titles={dashboard:"Overview",wallets:"Wallet Overview",exchange:"Currency exchange",transactions:"Transaction history",transfer:"Transfer result"};
export default function Navbar({onMenu,onSignOut}) {
  const {session}=useSession(),location=useLocation();
  const [open,setOpen]=useState(false),container=useRef(null),trigger=useRef(null),menu=useRef(null);
  useEffect(()=>{
    if(!open)return;
    function outside(event){if(!container.current?.contains(event.target))setOpen(false);}
    document.addEventListener("pointerdown",outside);
    return ()=>document.removeEventListener("pointerdown",outside);
  },[open]);
  function close(restore=false){setOpen(false);if(restore)trigger.current?.focus();}
  function reveal(last=false){setOpen(true);requestAnimationFrame(()=>{const items=menu.current?.querySelectorAll('[role="menuitem"]');if(items?.length)items[last?items.length-1:0].focus();});}
  function keys(event){
    if(event.key==="Escape"){event.preventDefault();close(true);return;}
    if(event.key==="Tab"){close();return;}
    if(!["ArrowDown","ArrowUp","Home","End"].includes(event.key))return;
    event.preventDefault();const items=[...menu.current.querySelectorAll('[role="menuitem"]')],index=items.indexOf(document.activeElement);
    const next=event.key==="Home"?0:event.key==="End"?items.length-1:(index+(event.key==="ArrowDown"?1:-1)+items.length)%items.length;
    items[next]?.focus();
  }
  return <header className="topbar">
    <div className="breadcrumb"><button className="icon-button menu-button" onClick={onMenu} aria-label="Open navigation"><Menu/></button><span>Your workspace</span><span className="crumb-slash">/</span><strong>{titles[location.pathname.split("/")[1]]||"Overview"}</strong></div>
    <div className="profile-dropdown" ref={container}>
      <button ref={trigger} className="profile profile-trigger" type="button" aria-label="Account menu" aria-haspopup="menu" aria-expanded={open} aria-controls="account-menu" onClick={()=>open?close():reveal()} onKeyDown={event=>{if(event.key==="ArrowDown"||event.key==="ArrowUp"){event.preventDefault();reveal(event.key==="ArrowUp");}else if(event.key==="Escape")close(true);}}>
        <span className="avatar">{session.customerName?.split(" ").map(s=>s[0]).slice(0,2).join("")}</span>
        <div><strong>{session.customerName}</strong><small>Personal account</small></div><ChevronDown size={15} className={open?"profile-chevron expanded":"profile-chevron"}/>
      </button>
      {open && <div id="account-menu" ref={menu} className="profile-menu" role="menu" aria-label="Account actions" onKeyDown={keys}>
        <Link role="menuitem" tabIndex={-1} to="/dashboard" onClick={()=>close()}><LayoutDashboard size={17}/>Dashboard</Link>
        <Link role="menuitem" tabIndex={-1} to="/dashboard?funding=deposit" onClick={()=>close()}><ArrowDownLeft size={17}/>Deposit money</Link>
        <Link role="menuitem" tabIndex={-1} to="/dashboard?funding=withdraw" onClick={()=>close()}><ArrowUpRight size={17}/>Withdraw money</Link>
        <div className="profile-menu-divider" role="separator"/>
        <button type="button" role="menuitem" tabIndex={-1} onClick={()=>{close();onSignOut();}}><LogOut size={17}/>Sign out</button>
      </div>}
    </div>
  </header>;
}
