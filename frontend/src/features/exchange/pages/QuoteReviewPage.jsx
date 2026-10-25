import { useEffect, useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import {
  ArrowRight,
  ArrowLeft,
  Clock3,
  ShieldCheck,
  Check,
  Copy,
  RefreshCw,
} from "lucide-react";
import useSession from "../../../hooks/useSession";
import useCountdown from "../../../hooks/useCountdown";
import { quotesApi } from "../api/quotesApi";
import { money, dateTime } from "../../../lib/format";
import Card from "../../../components/ui/Card";
import Button from "../../../components/ui/Button";
import StatusBadge from "../../../components/ui/StatusBadge";
import ErrorMessage from "../../../components/feedback/ErrorMessage";
import LoadingSpinner from "../../../components/feedback/LoadingSpinner";
export default function QuoteReviewPage() {
  const { quoteId } = useParams(),
    navigate = useNavigate(),
    { session } = useSession();
  const [view, setView] = useState(null),
    [error, setError] = useState(""),
    [busy, setBusy] = useState(""),
    [unknown, setUnknown] = useState(
      () =>
        !!sessionStorage.getItem(
          `fx-accept-state:${session.username}:${quoteId}`,
        ),
    ),
    [copied, setCopied] = useState(false);
  const remaining = useCountdown(view);
  useEffect(() => {
    let active = true;
    quotesApi
      .get(quoteId)
      .then((data) => {
        if (active) setView(data);
      })
      .catch((e) => {
        if (active) setError(e.message);
      });
    return () => {
      active = false;
    };
  }, [quoteId]);
  if (!view)
    return error ? (
      <ErrorMessage message={error} />
    ) : (
      <LoadingSpinner label="Retrieving your fixed quote…" />
    );
  const q = view.quote,
    status = q.status === "OPEN" && remaining === 0 ? "EXPIRED" : q.status;
  const open = status === "OPEN";
  async function accept() {
    setBusy("accept");
    setError("");
    const storageKey = `fx-accept:${session.username}:${quoteId}`;
    let key = sessionStorage.getItem(storageKey);
    if (!key) {
      key = crypto.randomUUID();
      sessionStorage.setItem(storageKey, key);
    }
    const stateKey = `fx-accept-state:${session.username}:${quoteId}`;
    sessionStorage.setItem(stateKey, "pending");
    try {
      const result = await quotesApi.accept(quoteId, key);
      if (result.status !== "COMPLETED" || !result.settlementId)
        throw new Error(
          "Settlement is not confirmed. Check history before retrying.",
        );
      sessionStorage.removeItem(stateKey);
      navigate("/transfer/result/" + result.settlementId, {
        state: { quoteId },
      });
    } catch (e) {
      const uncertain =
        !e.response || [500, 502, 504].includes(e.response?.status);
      setUnknown(uncertain);
      if (uncertain) sessionStorage.setItem(stateKey, "unknown");
      else sessionStorage.removeItem(stateKey);
      setError(e.message);
    } finally {
      setBusy("");
    }
  }
  async function cancel() {
    setBusy("cancel");
    setError("");
    try {
      setView(await quotesApi.cancel(quoteId));
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy("");
    }
  }
  return (
    <div className="page-enter">
      <Link className="back-link" to="/exchange">
        <ArrowLeft size={16} /> Back to exchange
      </Link>
      <div className="page-heading">
        <div>
          <p className="eyebrow">REVIEW BEFORE YOU COMMIT</p>
          <h1>Your offer. A little more certain.</h1>
          <p>
            These are your stored amounts. Market changes will not reprice this
            quote.
          </p>
        </div>
        <span className="step-pill">
          02 <span>/</span> 02 <small>REVIEW YOUR QUOTE</small>
        </span>
      </div>
      <ErrorMessage message={error} />
      <div className="exchange-grid">
        <Card className="quote-card">
          <div className="section-heading">
            <h2>Your fixed offer</h2>
            <StatusBadge status={status} />
          </div>
          <div className="quote-receive">
            <span>You receive</span>
            <h2>
              {money(q.targetAmount)} <small>{q.targetCurrency}</small>
            </h2>
            <p>In your selected USD wallet</p>
          </div>
          <div className="quote-pay">
            <span>You send</span>
            <strong>
              {money(q.sourceAmount)} {q.sourceCurrency}
            </strong>
          </div>
          <dl className="detail-list">
            <div>
              <dt>Offered exchange rate</dt>
              <dd>1 EUR = {q.offeredRate} USD</dd>
            </div>
            <div>
              <dt>Business spread</dt>
              <dd>{q.spreadBps} basis points</dd>
            </div>
            <div>
              <dt>From / to</dt>
              <dd>
                Wallet {q.sourceWalletId} / Wallet {q.targetWalletId}
              </dd>
            </div>
            <div>
              <dt>Valid until</dt>
              <dd>{dateTime(q.expiresAt)}</dd>
            </div>
          </dl>
          <div
            className={`countdown ${remaining < 15 ? "urgent" : ""}`}
            role="timer"
            aria-label="Time remaining"
          >
            <Clock3 size={19} />
            <span>
              {open
                ? "Your offer expires in"
                : status === "EXPIRED"
                  ? "This offer has expired"
                  : "Offer status"}
            </span>
            <strong>
              {open
                ? `${String(Math.floor(remaining / 60)).padStart(2, "0")}:${String(remaining % 60).padStart(2, "0")}`
                : status}
            </strong>
          </div>
          {unknown && (
            <div className="soft-note">
              <strong>Confirmation is still unknown.</strong>
              <p>
                Check your history first. A retry uses the same request key and
                must not create a second transfer.
              </p>
              <Link to="/transactions">
                Check transaction history <ArrowRight size={15} />
              </Link>
            </div>
          )}
          {open || unknown ? (
            <>
              <Button
                className="full-width"
                busy={busy === "accept"}
                disabled={!!busy && busy !== "accept"}
                onClick={accept}
              >
                {unknown
                  ? "Retry with the same key"
                  : "Accept quote & transfer"}
                <ArrowRight size={18} />
              </Button>
              {open && !unknown && (
                <Button
                  variant="text"
                  className="full-width"
                  disabled={!!busy}
                  onClick={cancel}
                >
                  Cancel quote
                </Button>
              )}
            </>
          ) : (
            <Link className="button button-primary full-width" to="/exchange">
              <RefreshCw size={18} /> Get a new quote
            </Link>
          )}
          <p className="form-assurance">
            <ShieldCheck size={15} /> The service checks status, expiry and
            funds again.
          </p>
        </Card>
        <div className="exchange-side">
          <Card className="confidence-card">
            <p className="eyebrow">YOUR QUOTE CONFIDENCE</p>
            <div className="confidence-score">
              <span>{q.confidence.score}</span>
              <small>/ 100</small>
              <StatusBadge status={q.confidence.label} />
            </div>
            <h2>Understand the inputs.</h2>
            <p className="muted">
              A view of data quality, not a prediction of the market.
            </p>
            <ul className="confidence-reasons">
              {q.confidence.reasons.map((reason, i) => (
                <li key={i}>
                  <Check size={17} />
                  {reason}
                </li>
              ))}
            </ul>
            <div className="confidence-weights">
              <span>
                Freshness <b>40%</b>
              </span>
              <span>
                Anomaly status <b>20%</b>
              </span>
              <span>
                Provider health <b>20%</b>
              </span>
              <span>
                Movement stability <b>20%</b>
              </span>
            </div>
            <p className="hint">
              Confidence never overrides a failed safety check.
            </p>
          </Card>
          <Card className="quote-reference">
            <span className="small-label">KEEP YOUR QUOTE REFERENCE</span>
            <code>{q.quoteId}</code>
            <button
              className="text-button"
              onClick={async () => {
                try {
                  await navigator.clipboard.writeText(q.quoteId);
                  setCopied(true);
                } catch {
                  setError("Select and copy the quote reference manually.");
                }
              }}
            >
              <Copy size={15} />
              {copied ? "Copied" : "Copy reference"}
            </button>
          </Card>
        </div>
      </div>
    </div>
  );
}
