import { useState } from "react";
import { Link, useNavigate, useLocation } from "react-router-dom";
import {
  ArrowUpRight,
  ShieldCheck,
  Eye,
  EyeOff,
  ArrowRight,
} from "lucide-react";
import useSession from "../../../hooks/useSession";
import Button from "../../../components/ui/Button";
import Input from "../../../components/ui/Input";
import ErrorMessage from "../../../components/feedback/ErrorMessage";
import "../auth.css";
export default function LoginPage() {
  const { login } = useSession(),
    navigate = useNavigate(),
    location = useLocation();
  const [demo, setDemo] = useState(false);
  const [show, setShow] = useState(false),
    [busy, setBusy] = useState(false),
    [error, setError] = useState("");
  async function submit(e) {
    e.preventDefault();
    setBusy(true);
    setError("");
    const data = new FormData(e.currentTarget);
    try {
      await login(
        String(data.get("username")).trim(),
        String(data.get("password")),
        demo,
      );
      const target = location.state?.from;
      navigate(
        target?.startsWith("/") && !target.startsWith("//")
          ? target
          : "/dashboard",
        { replace: true },
      );
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  }
  return (
    <div className="customer-app login-page">
      <section className="login-story">
        <Link className="brand" to="/">
          fx<span>lock</span>
          <ArrowUpRight size={25} />
        </Link>
        <div>
          <p className="eyebrow">A CLEARER WAY TO CONVERT</p>
          <h1>
            Your money.
            <br />
            Your next move.
            <br />
            <em>More certain.</em>
          </h1>
          <p>
            Get a fixed currency quote, take a moment to review it, and move
            forward with clarity.
          </p>
        </div>
        <span className="login-assurance">
          <ShieldCheck size={20} /> Review first. Accept when you’re ready.
        </span>
      </section>
      <section className="login-form-wrap">
        <Link className="back-link" to="/">
          Back to FXLock <ArrowUpRight size={15} />
        </Link>
        <div className="login-form">
          <p className="eyebrow">WELCOME BACK</p>
          <h2>
            Let’s pick up
            <br />
            where you left off.
          </h2>
          <p className="muted">Sign in to your currency workspace.</p>
          {location.state?.registered && <p className="registration-success" role="status">Account created. Sign in with your email and password.</p>}
          <ErrorMessage message={error} />
          <form onSubmit={submit}>
            <Input
              id="username"
              label={demo ? "Demo username" : "Email"}
              type={demo ? "text" : "email"}
              name="username"
              autoComplete="username"
              defaultValue={location.state?.email || ""}
              placeholder={demo ? "Your demo username" : "you@example.com"}
              required
            />
            <div className="password-field">
              <Input
                id="password"
                label="Password"
                name="password"
                type={show ? "text" : "password"}
                autoComplete="current-password"
                placeholder="Your password"
                required
              />
              <button
                className="icon-button"
                type="button"
                aria-label={show ? "Hide password" : "Show password"}
                onClick={() => setShow(!show)}
              >
                {show ? <EyeOff size={19} /> : <Eye size={19} />}
              </button>
            </div>
            <Button type="submit" busy={busy}>
              Sign in <ArrowRight size={18} />
            </Button>
            <Link className="button button-secondary signup-link" to="/register" aria-disabled={busy} onClick={event=>{if(busy)event.preventDefault();}}>Create account <ArrowRight size={18}/></Link>
          </form>
          <button type="button" className="text-button" disabled={busy} onClick={() => { setDemo(!demo); setError(""); }}>{demo ? "Use email login" : "Use demo username"}</button>
          <p className="hint">
            Use your existing FXLock account or create one to get started.
          </p>
        </div>
        <small className="login-footnote">
          Learning prototype. All wallet balances and transfers are simulated.
        </small>
      </section>
    </div>
  );
}
