import { NavLink, Link } from "react-router-dom";
import {
  House,
  Wallet,
  ArrowLeftRight,
  ReceiptText,
  ArrowUpRight,
  ShieldCheck,
  X,
} from "lucide-react";
const links = [
  ["/dashboard", House, "Home"],
  ["/wallets", Wallet, "Wallet Overview"],
  ["/exchange", ArrowLeftRight, "Currency exchange"],
  ["/transactions", ReceiptText, "Transaction history"],
];
export default function Sidebar({ open, onClose }) {
  return (
    <>
      <button
        className={`sidebar-backdrop ${open ? "visible" : ""}`}
        onClick={onClose}
        aria-label="Close navigation"
      />
      <aside className={`sidebar ${open ? "open" : ""}`}>
        <Link to="/dashboard" className="brand">
          fx<span>lock</span>
          <ArrowUpRight size={23} />
        </Link>
        <button
          className="icon-button close-nav"
          onClick={onClose}
          aria-label="Close navigation"
        >
          <X />
        </button>
        <p className="nav-label">YOUR WORKSPACE</p>
        <nav aria-label="Customer navigation">
          {links.map(([path, Icon, label]) => (
            <NavLink key={path} to={path} onClick={onClose}>
              <Icon size={20} />
              {label}
            </NavLink>
          ))}
        </nav>
        <div className="sidebar-bottom">
          <div className="safety-icon">
            <ShieldCheck size={23} />
          </div>
          <h3>A little more certainty.</h3>
          <p>Review your fixed offer before you make your next move.</p>
          <Link to="/exchange">
            Make a conversion <ArrowUpRight size={16} />
          </Link>
          <div className="workspace-label">
            <span /> Learning workspace
          </div>
        </div>
      </aside>
    </>
  );
}
