import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { ArrowDown, ArrowRight, ShieldCheck, Clock3, Info } from "lucide-react";
import useSession from "../../../hooks/useSession";
import Card from "../../../components/ui/Card";
import Button from "../../../components/ui/Button";
import ErrorMessage from "../../../components/feedback/ErrorMessage";
import { quotesApi } from "../api/quotesApi";
import { money, validAmount } from "../../../lib/format";
export default function NewQuotePage() {
  const { session } = useSession(),
    navigate = useNavigate();
  const [options, setOptions] = useState([]),
    [amount, setAmount] = useState("1000.00"),
    [source, setSource] = useState(""),
    [target, setTarget] = useState(""),
    [loading, setLoading] = useState(true),
    [busy, setBusy] = useState(false),
    [error, setError] = useState(""),
    [validation, setValidation] = useState("");
  useEffect(() => {
    let active = true;
    quotesApi
      .options()
      .then((data) => {
        if (active) {
          setOptions(data);
          setSource(
            data.find((w) => w.currency === "EUR" && w.canDebit)?.walletId ||
              "",
          );
          setTarget(data.find((w) => w.currency === "USD")?.walletId || "");
        }
      })
      .catch((e) => {
        if (active) setError(e.message);
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, []);
  async function submit(e) {
    e.preventDefault();
    const invalid = validAmount(
      amount,
      session.settings.minAmount,
      session.settings.maxAmount,
    );
    setValidation(invalid);
    if (invalid) return;
    setBusy(true);
    setError("");
    try {
      const data = await quotesApi.create({
        sourceCurrency: "EUR",
        targetCurrency: "USD",
        sourceAmount: amount,
        sourceWalletId: source,
        targetWalletId: target,
      });
      sessionStorage.setItem(
        "fx-last-quote:" + session.username,
        data.quote.quoteId,
      );
      navigate("/exchange/quote/" + data.quote.quoteId);
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  }
  return (
    <div className="page-enter">
      <div className="page-heading">
        <div>
          <p className="eyebrow">YOUR NEXT CURRENCY MOVE</p>
          <h1>A clear offer starts here.</h1>
          <p>Choose your wallets and tell us how much you’d like to send.</p>
        </div>
        <span className="step-pill">
          01 <span>/</span> 02 <small>REQUEST A QUOTE</small>
        </span>
      </div>
      <div className="exchange-grid">
        <Card className="exchange-card">
          <div className="section-heading">
            <h2>Make a conversion</h2>
            <span className="badge">EUR to USD</span>
          </div>
          <ErrorMessage message={error} />
          <form onSubmit={submit}>
            <div className="money-input-box">
              <label htmlFor="sourceAmount">You send</label>
              <div>
                <input
                  id="sourceAmount"
                  name="amount"
                  inputMode="decimal"
                  value={amount}
                  onChange={(e) => {
                    setAmount(e.target.value);
                    setValidation("");
                  }}
                  aria-invalid={!!validation}
                  aria-describedby="amount-rules"
                  autoComplete="off"
                />
                <span>
                  <span className="currency-icon eur">€</span>EUR
                </span>
              </div>
              <small>From your euro wallet</small>
            </div>
            <p
              id="amount-rules"
              className={validation ? "field-error" : "hint"}
            >
              {validation ||
                `Allowed amount: ${money(session.settings.minAmount)}–${money(session.settings.maxAmount)} EUR`}
            </p>
            <div className="exchange-direction" aria-hidden="true">
              <ArrowDown size={22} />
            </div>
            <div className="destination-box">
              <span>You receive</span>
              <div>
                <strong>Fixed amount after quote</strong>
                <span className="currency-chip">$ USD</span>
              </div>
              <p>Your final USD amount is calculated by the service.</p>
            </div>
            <div className="account-selects">
              <div className="field">
                <label htmlFor="sourceWallet">Sender EUR wallet</label>
                <select
                  id="sourceWallet"
                  value={source}
                  onChange={(e) => setSource(e.target.value)}
                  required
                  disabled={loading}
                >
                  <option value="">
                    {loading ? "Loading accounts…" : "Select a wallet"}
                  </option>
                  {options
                    .filter((w) => w.currency === "EUR" && w.canDebit)
                    .map((w) => (
                      <option key={w.walletId} value={w.walletId}>
                        {w.label} · {w.walletId}
                      </option>
                    ))}
                </select>
              </div>
              <div className="field">
                <label htmlFor="targetWallet">Receiving USD wallet</label>
                <select
                  id="targetWallet"
                  value={target}
                  onChange={(e) => setTarget(e.target.value)}
                  required
                  disabled={loading}
                >
                  <option value="">
                    {loading ? "Loading accounts…" : "Select a wallet"}
                  </option>
                  {options
                    .filter((w) => w.currency === "USD")
                    .map((w) => (
                      <option key={w.walletId} value={w.walletId}>
                        {w.label} · {w.walletId}
                      </option>
                    ))}
                </select>
              </div>
            </div>
            <Button
              className="full-width"
              type="submit"
              busy={busy}
              disabled={loading || !source || !target}
            >
              Get my fixed quote <ArrowRight size={18} />
            </Button>
            <p className="form-assurance">
              <ShieldCheck size={15} /> Requesting a quote does not move your
              money.
            </p>
          </form>
        </Card>
        <div className="exchange-side">
          <Card className="explain-card">
            <span className="rate-icon">
              <ShieldCheck size={23} />
            </span>
            <p className="eyebrow">CLARITY BEFORE COMMITMENT</p>
            <h2>
              Take a moment.
              <br />
              <em>Make it an informed one.</em>
            </h2>
            <p>
              Your quote will show the exact amount, offered rate and confidence
              explanation.
            </p>
            <ul>
              <li>
                <Clock3 size={19} />
                <span>
                  <strong>
                    {session.settings.quoteSeconds} seconds to review
                  </strong>
                  <small>A fixed offer, with a clear deadline.</small>
                </span>
              </li>
              <li>
                <Info size={19} />
                <span>
                  <strong>Pricing you can understand</strong>
                  <small>
                    Configured spread: {session.settings.spreadBps} basis
                    points.
                  </small>
                </span>
              </li>
              <li>
                <ShieldCheck size={19} />
                <span>
                  <strong>Checks before every offer</strong>
                  <small>Freshness, rate safety and account eligibility.</small>
                </span>
              </li>
            </ul>
          </Card>
          <div className="soft-note">
            {session.rate.available ? (
              <>
                <span className="small-label">INDICATIVE MARKET RATE</span>
                <strong>1 EUR ≈ {session.rate.marketRate} USD</strong>
                <p>This market observation is not a locked customer offer.</p>
              </>
            ) : (
              <>
                <strong>Market rate unavailable</strong>
                <p>{session.rate.message}</p>
                <p>
                  The service checks availability again when you request a
                  quote.
                </p>
              </>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
