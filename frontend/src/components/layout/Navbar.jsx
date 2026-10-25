import { Menu, ChevronDown } from "lucide-react";
import { useLocation } from "react-router-dom";
import useSession from "../../hooks/useSession";
const titles = {
  dashboard: "Overview",
  exchange: "Currency exchange",
  transactions: "Transaction history",
  transfer: "Transfer result",
};
export default function Navbar({ onMenu }) {
  const { session } = useSession(),
    location = useLocation();
  return (
    <header className="topbar">
      <div className="breadcrumb">
        <button
          className="icon-button menu-button"
          onClick={onMenu}
          aria-label="Open navigation"
        >
          <Menu />
        </button>
        <span>Your workspace</span>
        <span className="crumb-slash">/</span>
        <strong>{titles[location.pathname.split("/")[1]] || "Overview"}</strong>
      </div>
      <div className="profile">
        <span className="avatar">
          {session.customerName
            ?.split(" ")
            .map((s) => s[0])
            .slice(0, 2)
            .join("")}
        </span>
        <div>
          <strong>{session.customerName}</strong>
          <small>Personal account</small>
        </div>
        <ChevronDown size={15} />
      </div>
    </header>
  );
}
