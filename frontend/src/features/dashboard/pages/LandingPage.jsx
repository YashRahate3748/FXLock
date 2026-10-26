import { Link } from "react-router-dom";
import {
  ArrowUpRight,
  ArrowRight,
  LockKeyhole,
  ReceiptText,
  ShieldCheck,
} from "lucide-react";
export default function LandingPage() {
  return (
    <div className="customer-app landing">
      <header>
        <Link to="/" className="brand">
          fx<span>lock</span>
          <ArrowUpRight />
        </Link>
        <nav>
          <a href="#how-it-works">How it works</a>
          <Link to="/rates">Market operations</Link>
        </nav>
        <Link to="/login" className="button button-primary">
          Sign in <ArrowUpRight size={17} />
        </Link>
      </header>
      <main>
        <section className="landing-hero">
          <div>
            <p className="eyebrow">CURRENCY EXCHANGE, WITH CLARITY</p>
            <h1>
              A clear amount.
              <br />A calmer
              <br />
              <em>next move.</em>
            </h1>
            <p>
              Know your offered exchange rate and the amount you’ll receive.
              Review a fixed quote before you accept.
            </p>
            <Link className="button button-primary" to="/login">
              Open your workspace <ArrowRight size={18} />
            </Link>
            <span className="hero-footnote">
              <ShieldCheck size={16} /> Every offer has a clear expiry.
            </span>
          </div>
          <div
            className="hero-illustration"
            aria-label="Illustration of the quote journey"
          >
            <div className="orbit orbit-one" />
            <div className="orbit orbit-two" />
            <div className="currency-token euro">€</div>
            <div className="currency-token dollar">$</div>
            <div className="illustration-card">
              <div className="small-label">YOUR NEXT CONVERSION</div>
              <div className="illustration-pair">
                <span>EUR</span>
                <ArrowRight />
                <span>USD</span>
              </div>
              <div className="illustration-steps">
                <span>
                  <span className="dot" /> Request a quote
                </span>
                <span>
                  <LockKeyhole size={16} /> Review your fixed offer
                </span>
                <span>
                  <ReceiptText size={16} /> Keep a clear record
                </span>
              </div>
              <p>Illustration only. Your actual offer comes from FXLock.</p>
            </div>
            <span className="floating-note">
              <ShieldCheck size={18} /> Clarity before you commit
            </span>
          </div>
        </section>
        <section id="how-it-works" className="landing-steps">
          <p className="eyebrow">THREE SIMPLE STEPS</p>
          <h2>Less guesswork. More understanding.</h2>
          <div>
            {[
              [
                "01",
                "Choose your wallets",
                "Select your accounts and the amount you’d like to exchange.",
              ],
              [
                "02",
                "Review your offer",
                "See your fixed amount, confidence and time remaining.",
              ],
              [
                "03",
                "Accept and track",
                "Confirm before expiry, then review the recorded result.",
              ],
            ].map(([n, title, text]) => (
              <article key={n}>
                <span>{n}</span>
                <h3>{title}</h3>
                <p>{text}</p>
              </article>
            ))}
          </div>
        </section>
      </main>
      <footer>
        FXLock learning prototype · Simulated wallets and transfers · EUR ↔ USD
      </footer>
    </div>
  );
}
