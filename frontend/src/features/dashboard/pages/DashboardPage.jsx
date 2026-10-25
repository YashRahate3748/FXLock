import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import {
  ArrowUpRight,
  ArrowRight,
  ArrowDownLeft,
  ArrowUpLeft,
  Wallet,
  Clock3,
  ShieldCheck,
} from "lucide-react";
import useSession from "../../../hooks/useSession";
import Card from "../../../components/ui/Card";
import StatusBadge from "../../../components/ui/StatusBadge";
import { apiClient } from "../../../services/apiClient";
import { endpoints } from "../../../services/endpoints";
import { money, dateTime, shortId, isZero } from "../../../lib/format";
export default function DashboardPage() {
  const { session, refresh } = useSession();
  const [recent, setRecent] = useState(null),
    [error, setError] = useState("");
  useEffect(() => {
    let active = true;
    refresh().catch(() => {});
    apiClient
      .get(endpoints.history + "?size=4")
      .then(({ data }) => {
        if (active) setRecent(data);
      })
      .catch((e) => {
        if (active) setError(e.message);
      });
    return () => {
      active = false;
    };
  }, [refresh]);
  return (
    <div className="page-enter">
      <div className="page-heading">
        <div>
          <p className="eyebrow">YOUR MONEY, AT A GLANCE</p>
          <h1>Good to see you, {session.customerName.split(" ")[0]}.</h1>
          <p>One place for your wallets, quotes and currency moves.</p>
        </div>
        <span className="date-chip">
          {new Date().toLocaleDateString(undefined, {
            month: "short",
            day: "numeric",
            year: "numeric",
          })}
        </span>
      </div>
      <section className="dashboard-hero">
        <div>
          <span className="badge hero-badge">
            <ShieldCheck size={13} /> A LITTLE MORE CERTAINTY
          </span>
          <h2>
            Your next move,
            <br />
            <em>with a clear amount.</em>
          </h2>
          <p>Get a fixed EUR to USD offer. Review it before you accept.</p>
          <Link className="button button-primary" to="/exchange">
            Make a conversion <ArrowUpRight size={19} />
          </Link>
        </div>
        <div className="hero-currency-art" aria-hidden="true">
          <span className="art-euro">€</span>
          <div className="art-arrow">
            <ArrowUpRight size={58} />
          </div>
          <span className="art-dollar">$</span>
          <span className="art-caption">
            EUR <ArrowRight size={16} /> USD
          </span>
        </div>
      </section>
      <div className="section-heading">
        <h2>Your currency wallets</h2>
        <span>Balances stay separate by currency</span>
      </div>
      <div className="wallet-grid">
        {session.wallets.map((wallet) => (
          <Card key={wallet.walletId} className="wallet-card">
            <div className="wallet-top">
              <span
                className={"currency-icon " + wallet.currency.toLowerCase()}
              >
                {wallet.currency === "EUR" ? "€" : "$"}
              </span>
              <span>
                {wallet.currency === "EUR" ? "Euro" : "US Dollar"}
                <small>
                  {wallet.currency} wallet · {wallet.walletId}
                </small>
              </span>
              <StatusBadge status={wallet.status} />
            </div>
            <p className="small-label">AVAILABLE BALANCE</p>
            <div className="wallet-balance">
              {money(wallet.balance)} <span>{wallet.currency}</span>
            </div>
            <Link to={"/transactions?walletId=" + wallet.walletId}>
              View activity <ArrowUpRight size={17} />
            </Link>
          </Card>
        ))}
        {session.wallets.length === 0 && (
          <Card>
            <Wallet />
            <h3>No wallets yet</h3>
            <p>
              Ask your wallet-service owner to set up an account before
              exchanging.
            </p>
          </Card>
        )}
      </div>
      <div className="dashboard-bottom">
        <Card className="recent-card">
          <div className="section-heading">
            <h2>Recent activity</h2>
            <Link to="/transactions">
              View all <ArrowUpRight size={16} />
            </Link>
          </div>
          {error ? (
            <p className="inline-warning">{error}</p>
          ) : recent === null ? (
            <p className="muted">Loading your activity…</p>
          ) : recent.entries.length === 0 ? (
            <div className="empty-state compact">
              <span className="empty-icon">
                <ArrowRight size={25} />
              </span>
              <h3>A fresh start.</h3>
              <p>Your completed wallet transactions will appear here.</p>
              <Link to="/exchange">
                Get your first quote <ArrowRight size={16} />
              </Link>
            </div>
          ) : (
            <div className="activity-list">
              {recent.entries.map((entry) => {
                const debit = !isZero(entry.amountDebited);
                return (
                  <Link
                    key={entry.ledgerId}
                    to={"/transactions/" + entry.settlementId}
                  >
                    <span
                      className={
                        "activity-icon " + (debit ? "debit" : "credit")
                      }
                    >
                      {debit ? (
                        <ArrowUpLeft size={19} />
                      ) : (
                        <ArrowDownLeft size={19} />
                      )}
                    </span>
                    <span>
                      <strong>
                        {entry.currency} {debit ? "sent" : "received"}
                      </strong>
                      <small>
                        {dateTime(entry.transactionDate)} ·{" "}
                        {shortId(entry.settlementId)}
                      </small>
                    </span>
                    <strong>
                      {debit ? "−" : "+"}
                      {money(
                        debit ? entry.amountDebited : entry.amountCredited,
                      )}{" "}
                      <small>{entry.currency}</small>
                    </strong>
                  </Link>
                );
              })}
            </div>
          )}
        </Card>
        <Card className="rate-card">
          <span className="rate-icon">
            <ArrowUpRight size={22} />
          </span>
          <p className="eyebrow">MARKET SNAPSHOT</p>
          <h2>EUR / USD</h2>
          {session.rate.available ? (
            <>
              <div className="market-rate">{session.rate.marketRate}</div>
              <p className="hint">USD per EUR · Indicative market rate</p>
              <span className="rate-availability">
                <span /> Trusted observation available
              </span>
              <p className="hint">
                Observed {dateTime(session.rate.observedAt)}
              </p>
            </>
          ) : (
            <>
              <h3>Rate unavailable</h3>
              <p className="hint">{session.rate.message}</p>
            </>
          )}
          <div className="rate-rule">
            <Clock3 size={17} />
            <span>
              Fixed offers last {session.settings.quoteSeconds} seconds.
            </span>
          </div>
          <Link to="/exchange">
            Get a fixed quote <ArrowRight size={16} />
          </Link>
        </Card>
      </div>
      <p className="prototype-note">
        <ShieldCheck size={15} /> Learning prototype. All wallet balances and
        transactions are simulated.
      </p>
    </div>
  );
}
