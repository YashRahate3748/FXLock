import React, { useCallback, useRef, useState } from "react";
import useRatesPolling from "../../../hooks/useRatesPolling";
import { ratesApi } from "../api/ratesApi";
import {
  Badge,
  ErrorNotice,
  StatusCards,
  formatRate,
  formatTime,
} from "../components/RatesShared";

function AnomalyTable({ filter, page, onPage, onSelect, refreshKey }) {
  const load = useCallback(
    (signal) => ratesApi.anomalies(filter, page, signal),
    [filter, page],
  );

  const queue = useRatesPolling(load);

  return (
    <>
      <ErrorNotice error={queue.error} retained={!!queue.data} />

      <div className="fx-table-wrap">
        <table>
          <thead>
            <tr>
              <th>Anomaly</th>
              <th>Movement</th>
              <th>Status</th>
              <th>Details</th>
            </tr>
          </thead>

          <tbody>
            {queue.data?.items.map((item) => (
              <tr key={item.anomalyId}>
                <td>
                  <strong>#{item.anomalyId}</strong>
                  <small>{formatTime(item.createdAt)}</small>
                </td>
                <td>
                  {item.movementPercent}%
                  <small>Threshold {item.thresholdPercent}%</small>
                </td>
                <td>
                  <Badge value={item.status} />
                </td>
                <td>
                  <button onClick={() => onSelect(item.anomalyId)}>
                    Inspect
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {!queue.data?.items.length && (
        <div className="fx-empty">
          {queue.loading
            ? "Loading anomalies…"
            : queue.error
              ? "Anomalies unavailable."
              : "No anomalies match this filter."}
        </div>
      )}

      <div className="fx-pagination">
        <span>{queue.data?.totalElements ?? "—"} matching anomalies</span>
        <button
          disabled={page === 0 || queue.loading}
          onClick={() => onPage(page - 1)}
        >
          Previous
        </button>
        <span>
          {page + 1} / {Math.max(1, queue.data?.totalPages || 0)}
        </span>
        <button
          disabled={
            queue.loading || page + 1 >= (queue.data?.totalPages || 0)
          }
          onClick={() => onPage(page + 1)}
        >
          Next
        </button>
      </div>
    </>
  );
}

export default function RiskMonitorPage() {
  const status = useRatesPolling(ratesApi.status);

  const [filter, setFilter] = useState("OPEN");
  const [page, setPage] = useState(0);
  const [revision, setRevision] = useState(0);
  const [selected, setSelected] = useState(null);
  const [action, setAction] = useState(null);
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const [message, setMessage] = useState("");

  const dialog = useRef(null);
  const selectionVersion = useRef(0);

  function refresh() {
    status.refresh();
    setRevision((value) => value + 1);
  }

  async function inspect(id) {
    const version = ++selectionVersion.current;

    setSelected(null);
    setError(null);

    try {
      const item = await ratesApi.anomaly(id);
      if (version === selectionVersion.current) setSelected(item);
    } catch (failure) {
      if (version === selectionVersion.current) setError(failure);
    }
  }

  async function openReview(nextAction) {
    if (!selected) return;

    setBusy(true);
    setError(null);

    try {
      const latest = await ratesApi.anomaly(selected.anomalyId);
      setSelected(latest);

      if (nextAction === "approve" && !latest.canApprove) {
        throw new Error(latest.approvalBlockedReason || "Cannot approve.");
      }

      if (nextAction === "reject" && !latest.canReject) {
        throw new Error("This anomaly has already been reviewed.");
      }

      setReason("");
      setAction(nextAction);
      dialog.current.showModal();
    } catch (failure) {
      setError(failure);
    } finally {
      setBusy(false);
    }
  }

  function openQuotingAction() {
    setError(null);
    setAction(status.data.quotingEnabled ? "disable" : "enable");
    dialog.current.showModal();
  }

  async function submit(event) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    setMessage("");

    try {
      if (action === "approve" || action === "reject") {
        // Recheck immediately before the mutation.
        const latest = await ratesApi.anomaly(selected.anomalyId);
        setSelected(latest);

        if (action === "approve" && !latest.canApprove) {
          throw new Error(latest.approvalBlockedReason || "Cannot approve.");
        }

        if (action === "reject" && !latest.canReject) {
          throw new Error("This anomaly has already been reviewed.");
        }

        await ratesApi.review(selected.anomalyId, action, reason.trim());
      } else {
        await ratesApi.setQuoting(action === "enable");
      }

      setSelected(null);
      setMessage("Action completed. Current data is being refreshed.");
    } catch (failure) {
      setError(failure);
    } finally {
      setBusy(false);
      dialog.current.close();
      refresh();
    }
  }

  const reviewing = action === "approve" || action === "reject";

  return (
    <>
      <header className="fx-page-head">
        <div>
          <p className="fx-eyebrow">RISK OPERATIONS</p>
          <h1>Review the unexpected.</h1>
          <p className="fx-muted">
            Inspect unusual observations and control new quote creation.
          </p>
        </div>
        <button onClick={refresh}>Refresh</button>
      </header>

      <ErrorNotice error={error} />
      <ErrorNotice error={status.error} retained={!!status.data} />

      {message && (
        <div className="fx-notice success" role="status">
          {message}
        </div>
      )}

      {status.data && (
        <>
          <StatusCards status={status.data} />

          <section className="fx-control">
            <div>
              <h2>
                New quote creation{" "}
                <Badge
                  value={status.data.quotingEnabled ? "ENABLED" : "DISABLED"}
                />
              </h2>
              <p>
                Existing quotes and settlements follow their own rules.
              </p>
            </div>

            <button
              className="fx-primary"
              disabled={busy || !!status.error}
              onClick={openQuotingAction}
            >
              {status.data.quotingEnabled ? "Pause quoting" : "Enable quoting"}
            </button>
          </section>
        </>
      )}

      <div className="fx-risk-grid">
        <section className="fx-panel">
          <div className="fx-section-head">
            <h2>Rate anomalies</h2>

            <label>
              Status
              <select
                value={filter}
                onChange={(event) => {
                  setFilter(event.target.value);
                  setPage(0);
                  setSelected(null);
                  selectionVersion.current += 1;
                }}
              >
                <option value="">All</option>
                {["OPEN", "ACKNOWLEDGED", "APPROVED", "REJECTED"].map(
                  (value) => (
                    <option key={value} value={value}>
                      {value}
                    </option>
                  ),
                )}
              </select>
            </label>
          </div>

          <AnomalyTable
            key={`${filter}-${page}-${revision}`}
            filter={filter}
            page={page}
            onPage={setPage}
            onSelect={inspect}
          />
        </section>

        <aside className="fx-panel">
          <p className="fx-eyebrow">OBSERVATION REVIEW</p>

          {selected ? (
            <>
              <h2>Anomaly #{selected.anomalyId}</h2>
              <Badge value={selected.status} />
              <p>{selected.reason}</p>

              <dl className="fx-details single">
                <div>
                  <dt>Candidate rate</dt>
                  <dd>{formatRate(selected.candidate?.marketRate)}</dd>
                </div>
                <div>
                  <dt>Previous trusted rate</dt>
                  <dd>{formatRate(selected.previousTrusted?.marketRate)}</dd>
                </div>
                <div>
                  <dt>Candidate observation</dt>
                  <dd>{formatTime(selected.candidate?.observationTime)}</dd>
                </div>
                <div>
                  <dt>Absolute movement / threshold</dt>
                  <dd>
                    {selected.movementPercent}% / {selected.thresholdPercent}%
                  </dd>
                </div>
              </dl>

              <div
                className="fx-meter"
                role="img"
                aria-label={`Movement ${selected.movementPercent} percent, threshold ${selected.thresholdPercent} percent`}
              >
                <span
                  style={{
                    width: `${Math.max(
                      0,
                      Math.min(
                        100,
                        (Number(selected.movementPercent) /
                          (Number(selected.thresholdPercent) || 1)) *
                          50,
                      ),
                    )}%`,
                  }}
                />
                <i />
              </div>

              <p className="fx-muted">
                Centre marker represents the configured anomaly threshold.
              </p>

              {selected.approvalBlockedReason && (
                <div className="fx-notice">
                  {selected.approvalBlockedReason}
                </div>
              )}

              {selected.reviewedAt && (
                <p className="fx-muted">
                  Reviewed by {selected.reviewedBy}
                  <br />
                  {formatTime(selected.reviewedAt)}
                  <br />
                  {selected.reviewReason}
                </p>
              )}

              <div className="fx-actions">
                <button
                  className="fx-primary"
                  disabled={busy || !selected.canApprove}
                  onClick={() => openReview("approve")}
                >
                  Approve
                </button>
                <button
                  disabled={busy || !selected.canReject}
                  onClick={() => openReview("reject")}
                >
                  Reject
                </button>
              </div>
            </>
          ) : (
            <div className="fx-empty">
              Select an anomaly to inspect its rates and review eligibility.
            </div>
          )}
        </aside>
      </div>

      <dialog
        ref={dialog}
        className="fx-dialog"
        onCancel={(event) => {
          if (busy) event.preventDefault();
        }}
      >
        <form onSubmit={submit}>
          <p className="fx-eyebrow">CONFIRM ACTION</p>
          <h2>
            {reviewing
              ? `${action === "approve" ? "Approve" : "Reject"} anomaly #${selected?.anomalyId}?`
              : `${action === "enable" ? "Enable" : "Pause"} new quote creation?`}
          </h2>

          <p>
            {reviewing
              ? "Your analyst identity and reason will be recorded."
              : "This changes new EUR/USD quote creation for all users."}
          </p>

          {reviewing && (
            <label>
              Review reason
              <textarea
                autoFocus
                required
                rows={4}
                maxLength={900}
                value={reason}
                onChange={(event) => setReason(event.target.value)}
              />
              <small>{reason.length} / 900</small>
            </label>
          )}

          <div className="fx-actions">
            <button
              type="button"
              disabled={busy}
              onClick={() => dialog.current.close()}
            >
              Cancel
            </button>
            <button
              className="fx-primary"
              disabled={busy || (reviewing && !reason.trim())}
            >
              {busy ? "Submitting…" : "Confirm"}
            </button>
          </div>
        </form>
      </dialog>
    </>
  );
}