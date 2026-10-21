function RateCard({
  rate,
  loading,
  onRefresh,
}) {
  return (
    <section className="card">
      <div className="card-header">
        <h2>Trusted FX Rate</h2>

        <button
          onClick={onRefresh}
          disabled={loading}
        >
          Refresh
        </button>
      </div>

      {loading && (
        <p>Loading trusted rate...</p>
      )}

      {!loading && !rate && (
        <p>
          No trusted rate loaded.
        </p>
      )}

      {!loading && rate && (
        <div className="rate-details">

          <div className="primary-rate">
            <span>
              {rate.currencyPair}
            </span>

            <strong>
              {rate.marketRate}
            </strong>
          </div>

          <div className="details-grid">

            <div>
              <span className="label">
                Snapshot ID
              </span>
              <span>
                {rate.snapshotId}
              </span>
            </div>

            <div>
              <span className="label">
                Fresh
              </span>

              <span
                className={
                  rate.fresh
                    ? "badge success"
                    : "badge danger"
                }
              >
                {rate.fresh
                  ? "YES"
                  : "NO"}
              </span>
            </div>

            <div>
              <span className="label">
                Age
              </span>
              <span>
                {rate.ageSeconds} seconds
              </span>
            </div>

            <div>
              <span className="label">
                Provider
              </span>

              <span
                className={
                  rate.providerHealth === "UP"
                    ? "badge success"
                    : "badge warning"
                }
              >
                {rate.providerHealth}
              </span>
            </div>

            <div>
              <span className="label">
                Observation Time
              </span>
              <span>
                {formatDate(
                  rate.observationTime
                )}
              </span>
            </div>

            <div>
              <span className="label">
                Received At
              </span>
              <span>
                {formatDate(
                  rate.receivedAt
                )}
              </span>
            </div>

            <div>
              <span className="label">
                Movement %
              </span>
              <span>
                {rate.movementPercent}
              </span>
            </div>

            <div>
              <span className="label">
                Quoting Enabled
              </span>

              <span
                className={
                  rate.quotingEnabled
                    ? "badge success"
                    : "badge danger"
                }
              >
                {rate.quotingEnabled
                  ? "YES"
                  : "NO"}
              </span>
            </div>

          </div>

          <h3>Risk Scores</h3>

          <div className="details-grid">

            <Score
              label="Freshness"
              value={rate.freshnessScore}
            />

            <Score
              label="Anomaly"
              value={rate.anomalyScore}
            />

            <Score
              label="Provider Health"
              value={
                rate.providerHealthScore
              }
            />

            <Score
              label="Stability"
              value={rate.stabilityScore}
            />

          </div>
        </div>
      )}
    </section>
  );
}

function Score({
  label,
  value,
}) {
  return (
    <div>
      <span className="label">
        {label}
      </span>

      <span>
        {value}
      </span>
    </div>
  );
}

function formatDate(value) {

  if (!value) {
    return "-";
  }

  return new Date(value)
    .toLocaleString();
}

export default RateCard;