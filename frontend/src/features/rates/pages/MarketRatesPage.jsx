import React, { useCallback, useState } from "react";
import useRatesPolling from "../../../hooks/useRatesPolling";
import { ratesApi } from "../api/ratesApi";
import {
  Badge,
  ErrorNotice,
  RateChart,
  StatusCards,
  formatRate,
  formatTime,
} from "../components/RatesShared";

function HistoryPanel({ hours }) {
  const load = useCallback(
    (signal) => ratesApi.history(hours, signal),
    [hours],
  );

  const history = useRatesPolling(load, 30000);

  return (
    <>
      <ErrorNotice error={history.error} retained={!!history.data} />

      {history.data?.truncated && (
        <div className="fx-notice">
          Showing the first {history.data.limit} observations. Choose a shorter
          range to see the complete series.
        </div>
      )}

      {history.data ? (
        <>
          <RateChart items={history.data.items} />

          <details>
            <summary>View observation data</summary>

            <div className="fx-table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>Observed</th>
                    <th>Rate</th>
                    <th>Movement</th>
                    <th>Status</th>
                  </tr>
                </thead>
                <tbody>
                  {history.data.items.map((point) => (
                    <tr key={point.snapshotId}>
                      <td>{formatTime(point.observationTime)}</td>
                      <td>{formatRate(point.marketRate)}</td>
                      <td>
                        {point.movementPercent == null
                          ? "—"
                          : `${point.movementPercent}%`}
                      </td>
                      <td>
                        <Badge value={point.validationStatus} />
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </details>
        </>
      ) : (
        <div className="fx-empty">
          {history.loading ? "Loading observations…" : "History unavailable."}
        </div>
      )}

      <button onClick={history.refresh} disabled={history.loading}>
        Refresh chart
      </button>
    </>
  );
}

export default function MarketRatesPage() {
  const status = useRatesPolling(ratesApi.status);
  const [hours, setHours] = useState(1);

  return (
    <>
      <header className="fx-page-head">
        <div>
          <p className="fx-eyebrow">MARKET INTELLIGENCE</p>
          <h1>A clear view of the market.</h1>
          <p className="fx-muted">
            Trusted rates and the observations behind them.
          </p>
        </div>

        <button onClick={status.refresh} disabled={status.loading}>
          Refresh status
        </button>
      </header>

      <ErrorNotice error={status.error} retained={!!status.data} />

      {status.data ? (
        <StatusCards status={status.data} />
      ) : (
        <div className="fx-empty">
          {status.loading ? "Loading market status…" : "Status unavailable."}
        </div>
      )}

      <section className="fx-panel">
        <div className="fx-section-head">
          <div>
            <p className="fx-eyebrow">EUR / USD</p>
            <h2>Market observations</h2>
          </div>

          <label>
            Time range
            <select
              value={hours}
              onChange={(event) => setHours(Number(event.target.value))}
            >
              <option value={1}>Last hour</option>
              <option value={6}>Last 6 hours</option>
              <option value={24}>Last 24 hours</option>
            </select>
          </label>
        </div>

        <HistoryPanel key={hours} hours={hours} />
      </section>

      {status.data && (
        <section className="fx-panel">
          <h2>Observation details</h2>

          <dl className="fx-details">
            <div>
              <dt>Provider observation</dt>
              <dd>
                {formatTime(status.data.lastTrustedRate?.observationTime)}
              </dd>
            </div>
            <div>
              <dt>Received by FXLock</dt>
              <dd>{formatTime(status.data.lastTrustedRate?.receivedAt)}</dd>
            </div>
            <div>
              <dt>Freshness at last check</dt>
              <dd>
                {status.data.fresh ? "Fresh" : "Stale or unavailable"} ·{" "}
                {status.data.maxRateAgeSeconds}s limit
              </dd>
            </div>
            <div>
              <dt>Server checked at</dt>
              <dd>{formatTime(status.data.serverTime)}</dd>
            </div>
            <div>
              <dt>Anomaly threshold</dt>
              <dd>{status.data.anomalyThresholdPercent}%</dd>
            </div>
            <div>
              <dt>New quote creation</dt>
              <dd>{status.data.quotingEnabled ? "Enabled" : "Disabled"}</dd>
            </div>
          </dl>
        </section>
      )}

      <p className="fx-muted">
        Status refreshes every 15 seconds while this tab is visible. These are
        market rates; customer pricing and quote confidence belong to
        quote-service.
      </p>
    </>
  );
}