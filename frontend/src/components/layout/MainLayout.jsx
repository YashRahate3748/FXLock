import { useState } from "react";
import { Outlet, useNavigate } from "react-router-dom";
import { LogOut } from "lucide-react";
import Sidebar from "./Sidebar";
import Navbar from "./Navbar";
import useSession from "../../hooks/useSession";
import ErrorMessage from "../feedback/ErrorMessage";
export default function MainLayout() {
  const [open, setOpen] = useState(false),
    [error, setError] = useState("");
  const { logout } = useSession(),
    navigate = useNavigate();
  async function leave() {
    try {
      await logout();
      navigate("/login");
    } catch (e) {
      setError(e.message);
    }
  }
  return (
    <div className="customer-app">
      <a className="skip-link" href="#main-content">
        Skip to content
      </a>
      <Sidebar open={open} onClose={() => setOpen(false)} />
      <div className="workspace">
        <Navbar onMenu={() => setOpen(true)} />
        <main id="main-content">
          <ErrorMessage message={error} />
          <Outlet />
        </main>
        <footer className="app-footer">
          <span>FXLock · Currency clarity, from quote to record.</span>
          <button onClick={leave}>
            <LogOut size={14} /> Sign out
          </button>
        </footer>
      </div>
    </div>
  );
}
