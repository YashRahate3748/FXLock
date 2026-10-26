import { useEffect, useState } from "react";
import { Link, useSearchParams } from "react-router-dom";
import {
  ArrowUpRight,
  RefreshCw,
  ReceiptText,
  ChevronLeft,
  ChevronRight,
  Wallet,
} from "lucide-react";
import { apiClient } from "../../../services/apiClient";
import { readHistory } from "../../../services/compatibility";
import { endpoints } from "../../../services/endpoints";
import { money, dateTime, shortId, isZero } from "../../../lib/format";
import Card from "../../../components/ui/Card";
import Button from "../../../components/ui/Button";
import ErrorMessage from "../../../components/feedback/ErrorMessage";
import StatusBadge from "../../../components/ui/StatusBadge";
export default function TransactionHistoryPage() {
  const [params, setParams] = useSearchParams(),
    wallet = params.get("walletId") || "",
    search = params.get("search") || "",
    currency = params.get("currency") || "",
    fromDate = params.get("from") || "",
    toDate = params.get("to") || "",
    rawPage = Number(params.get("page") || 0),
    page = Number.isSafeInteger(rawPage) && rawPage >= 0 ? rawPage : 0;
  const [accounts, setAccounts] = useState([]),
    [data, setData] = useState(null),
    [busy, setBusy] = useState(true),
    [error, setError] = useState(""),
    [revision, setRevision] = useState(0);
  useEffect(() => {
    let active = true;
    apiClient
      .get(endpoints.accounts)
      .then((r) => {
        if (active) setAccounts(r.data);
      })
      .catch((e) => {
        if (active) setError(e.message);
      });
    return () => {
      active = false;
    };
  }, []);
  useEffect(() => {
    let active = true;
    const request = new AbortController();
    readHistory(apiClient, {
          walletId: wallet || undefined,
          page,
          size: 10,
          search: search || undefined,
          currency: currency || undefined,
          start: fromDate ? fromDate + "T00:00:00Z" : undefined,
          end: toDate
            ? Number.isFinite(Date.parse(toDate + "T00:00:00Z"))
              ? new Date(Date.parse(toDate + "T00:00:00Z") + 86400000).toISOString()
              : toDate
            : undefined,
      }, request.signal)
      .then((result) => {
        if (active) {
          setData(result);
          setError("");
        }
      })
      .catch((e) => {
        if (active) {
          setError(e.message);
          setData(null);
        }
      })
      .finally(() => {
        if (active) setBusy(false);
      });
    return () => {
      active = false;
      request.abort();
    };
  }, [wallet, page, revision, search, currency, fromDate, toDate]);
  function change(nextWallet, nextPage) {
    setBusy(true);
    const next = new URLSearchParams(params);
    if (nextWallet) next.set("walletId", nextWallet);
    else next.delete("walletId");
    if (nextPage) next.set("page", String(nextPage));
    else next.delete("page");
    setParams(next);
  }
  function filter(event) {
    event.preventDefault();
    const values = new FormData(event.currentTarget);
    const next = new URLSearchParams();
    if (wallet) next.set("walletId", wallet);
    for (const key of ["search", "currency", "from", "to"]) {
      const value = String(values.get(key) || "").trim();
      if (value) next.set(key, value);
    }
    if (next.toString() === params.toString()) setRevision((r) => r + 1);
    setBusy(true);
    setParams(next);
  }

  return (
    <div className="page-enter">
      <div className="page-heading">
        <div>
          <p className="eyebrow">EVERY MOVE, A CLEAR RECORD</p>
          <h1>Your transaction history.</h1>
          <p>Money in, money out, and the balance after each entry.</p>
        </div>
        <Link className="button button-primary" to="/exchange">
          Make a conversion <ArrowUpRight size={18} />
        </Link>
      </div>
      <Card className="history-summary">
        <span className="summary-icon">
          <ReceiptText size={25} />
        </span>
        <div>
          <span className="small-label">RECORDED WALLET ENTRIES</span>
          <strong>{data?.total ?? "—"}</strong>
        </div>
        <span>
          One conversion can create two entries:
          <br />
          <b>a source debit and a destination credit.</b>
        </span>
      </Card>
      <Card className="history-card">
        <div className="history-toolbar">
          <div className="field">
            <label htmlFor="history-wallet">Account</label>
            <select
              id="history-wallet"
              value={wallet}
              onChange={(e) => change(e.target.value, 0)}
            >
              <option value="">All my accounts</option>
              {accounts.map((a) => (
                <option key={a.walletId} value={a.walletId}>
                  {a.currency} wallet · {a.walletId}
                  {a.status === "ACTIVE" ? "" : ` · ${a.status}`}
                </option>
              ))}
            </select>
          </div>
          <div className="history-filter-note">
            <StatusBadge status="COMPLETED" />
            <span>Completed wallet movements</span>
          </div>
          <Button
            variant="secondary"
            busy={busy}
            onClick={() => {
              setBusy(true);
              setRevision((r) => r + 1);
            }}
          >
            <RefreshCw size={16} /> Refresh
          </Button>
        </div>
        <form
          className="history-search"
          onSubmit={filter}
          key={[search, currency, fromDate, toDate].join(":")}
        >
          <div className="field">
            <label htmlFor="history-search">Reference or customer name</label>
            <input
              id="history-search"
              name="search"
              placeholder="Search your records"
              maxLength={100}
              defaultValue={search}
            />
          </div>
          <div className="field">
            <label htmlFor="history-currency">Currency</label>
            <select
              id="history-currency"
              name="currency"
              defaultValue={currency}
            >
              <option value="">All currencies</option>
              <option>EUR</option>
              <option>USD</option>
            </select>
          </div>
          <div className="field">
            <label htmlFor="history-from">From date (UTC)</label>
            <input
              id="history-from"
              name="from"
              type="date"
              defaultValue={fromDate}
            />
          </div>
          <div className="field">
            <label htmlFor="history-to">To date (UTC)</label>
            <input
              id="history-to"
              name="to"
              type="date"
              defaultValue={toDate}
            />
          </div>
          <Button type="submit" variant="secondary" disabled={busy}>
            Apply filters
          </Button>
          {(search || currency || fromDate || toDate) && (
            <button
              className="text-button"
              type="button"
              onClick={() => {
                setBusy(true);
                setParams({});
              }}
            >
              Clear
            </button>
          )}
        </form>
        <p className="hint">Search, currency and date filters apply to all your loaded history, including deposits and withdrawals.</p>
        <ErrorMessage message={error} />
        {busy ? (
          <div className="loading" role="status">
            Loading your history…
          </div>
        ) : data?.entries.length ? (
          <>
            <div
              className="table-scroll"
              role="region"
              aria-label="Transaction history"
              tabIndex={0}
            >
              <table>
                <thead>
                  <tr>
                    <th>Date & time</th>
                    <th>Customer / account</th>
                    <th>Credited</th>
                    <th>Debited</th>
                    <th>Balance after</th>
                    <th>Status</th>
                    <th>Reference</th>
                  </tr>
                </thead>
                <tbody>
                  {data.entries.map((entry) => (
                    <tr key={entry.entryId || entry.ledgerId}>
                      <td>{dateTime(entry.transactionDate)}</td>
                      <td>
                        <strong>{entry.customerName}</strong>
                        <small>
                          <Wallet size={12} /> {entry.currency} wallet ·{" "}
                          {entry.walletId}
                        </small>
                      </td>
                      <td className="credit">
                        {!isZero(entry.amountCredited)
                          ? `${money(entry.amountCredited)} ${entry.currency}`
                          : "—"}
                      </td>
                      <td className="debit">
                        {!isZero(entry.amountDebited)
                          ? `${money(entry.amountDebited)} ${entry.currency}`
                          : "—"}
                      </td>
                      <td className="balance">
                        {money(entry.updatedBalance)}{" "}
                        <small>{entry.currency}</small>
                      </td>
                      <td>
                        <StatusBadge status="COMPLETED" />
                      </td>
                      <td>
                        {entry.settlementId ? <Link
                          className="reference-link"
                          to={"/transactions/" + entry.settlementId}
                          title={entry.settlementId}
                        >
                          {shortId(entry.settlementId)}
                          <ArrowUpRight size={14} />
                        </Link> : <span title={entry.transactionRef}>{entry.transactionType} · {shortId(entry.transactionRef)}</span>}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <div className="pagination">
              <span>
                Showing {page * 10 + 1}–{page * 10 + data.entries.length} of{" "}
                {data.total} entries
              </span>
              <div>
                <Button
                  variant="secondary"
                  disabled={page === 0}
                  onClick={() => change(wallet, page - 1)}
                >
                  <ChevronLeft size={16} /> Previous
                </Button>
                <Button
                  variant="secondary"
                  disabled={!data.hasNext}
                  onClick={() => change(wallet, page + 1)}
                >
                  Next <ChevronRight size={16} />
                </Button>
              </div>
            </div>
          </>
        ) : data ? (
          <div className="empty-state">
            <span className="empty-icon">
              <ReceiptText size={28} />
            </span>
            <h2>
              {page > 0
                ? "No entries on this page"
                : search || currency || fromDate || toDate
                  ? "No entries match your filters."
                  : "Your story starts with your first move."}
            </h2>
            <p>
              {page > 0
                ? "Return to the first page to view available entries."
                : "Completed wallet transactions will appear here. Requesting or cancelling a quote does not move money."}
            </p>
            {page > 0 ? (
              <Button variant="secondary" onClick={() => change(wallet, 0)}>
                Return to first page
              </Button>
            ) : (
              <Link className="button button-primary" to="/exchange">
                Get a quote <ArrowUpRight size={17} />
              </Link>
            )}
          </div>
        ) : null}
        <p className="table-footnote">
          Balances belong to the individual wallet at the time of the entry. EUR
          and USD are separate currencies. Times use your local time zone.
        </p>
      </Card>
    </div>
  );
}
