import React from "react";

export function formatTime(value) {
  if (value == null) return "—";

  return new Date(value).toLocaleString(undefined, {
    dateStyle: "medium",
    timeStyle: "medium",
  });
}

export function formatRate(value) {
  return value == null ? "—" : Number(value).toFixed(6);
}

export function Badge({ value }) {
  const good = ["UP", "TRUSTED", "APPROVED", "AVAILABLE", "ENABLED"];
  const bad = ["DOWN", "INVALID", "REJECTED", "UNAVAILABLE", "DISABLED"];

  const tone = good.includes(value)
    ? "good"
    : bad.includes(value)
      ? "bad"
      : "warning";

  return (
    <span className={`fx-badge ${tone}`}>
      {value?.replaceAll("_", " ") || "UNKNOWN"}
    </span>
  );
}

export function ErrorNotice({ error, retained = false }) {
  if (!error) return null;

  return (
    <div className="fx-notice error" role="alert">
      {error.message}
      {retained && " Previously loaded information may be outdated."}
    </div>
  );
}

export function StatusCards({ status }) {
  const movement = status.lastTrustedRate?.movementPercent;

  return (
    <div className="fx-metrics">
      <article>
        <span className="fx-eyebrow">EUR / USD</span>
        <strong>{formatRate(status.lastTrustedRate?.marketRate)}</strong>
        <small>USD per 1 EUR · trusted rate</small>
      </article>

      <article>
        <span className="fx-eyebrow">QUOTE AVAILABILITY</span>
        <strong>
          <Badge value={status.quoteAvailable ? "AVAILABLE" : "UNAVAILABLE"} />
        </strong>
        <small>{status.unavailableReason || "Fresh rate · quoting enabled"}</small>
      </article>

      <article>
        <span className="fx-eyebrow">PROVIDER HEALTH</span>
        <strong>
          <Badge value={status.providerHealth} />
        </strong>
        <small>Twelve Data ingestion</small>
      </article>

      <article>
        <span className="fx-eyebrow">ABSOLUTE MOVEMENT</span>
        <strong>
          {movement == null ? "—" : `${Number(movement).toFixed(4)}%`}
        </strong>
        <small>Against the previous trusted rate</small>
      </article>
    </div>
  );
}

export function RateChart({ items = [] }) {
  const points = items
    .filter(
      (point) =>
        point.observationTime &&
        point.marketRate != null &&
        Number.isFinite(Number(point.marketRate)) &&
        Number.isFinite(Date.parse(point.observationTime)),
    )
    .sort(
      (a, b) =>
        Date.parse(a.observationTime) -
        Date.parse(b.observationTime),
    );

  if (!points.length) {
    return (
      <div className="fx-empty">
        No observations in this time range.
      </div>
    );
  }

  const values = points.map((point) => Number(point.marketRate));
  const timestamps = points.map((point) =>
    Date.parse(point.observationTime),
  );

  const lowestRate = Math.min(...values);
  const highestRate = Math.max(...values);
  const observedRange = highestRate - lowestRate;
  const unchanged = observedRange === 0;

  /*
   * For changing rates, tightly fit the observed range.
   * For identical rates, provide a small display range without
   * pretending that movement occurred.
   *
   * MARKET_RATE supports up to ten decimal places.
   */
  const padding = unchanged
    ? Math.max(Math.abs(lowestRate) * 0.000001, 0.000000001)
    : Math.max(observedRange * 0.15, 0.0000000001);

  const minimum = lowestRate - padding;
  const maximum = highestRate + padding;

  const firstTime = Math.min(...timestamps);
  const lastTime = Math.max(...timestamps);

  const left = 150;
  const right = 950;
  const top = 30;
  const bottom = 240;

  const x = (timestamp) => {
    if (firstTime === lastTime) {
      return (left + right) / 2;
    }

    return (
      left +
      ((timestamp - firstTime) / (lastTime - firstTime)) *
        (right - left)
    );
  };

  const y = (value) =>
    bottom -
    ((value - minimum) / (maximum - minimum)) *
      (bottom - top);

  const tickCount = 5;
  const tickStep = (maximum - minimum) / (tickCount - 1);

  // Increase precision automatically for smaller ranges.
  const axisDecimals = Math.min(
    10,
    Math.max(5, Math.ceil(-Math.log10(tickStep)) + 1),
  );

  const formatAxis = (value) => value.toFixed(axisDecimals);

  const trusted = points.filter(
    (point) => point.validationStatus === "TRUSTED",
  );

  const colorFor = (status) => {
    if (status === "TRUSTED") return "#501B65";
    if (status === "SUSPICIOUS") return "#b45309";
    return "#dc2626";
  };

  const timeLabel = (timestamp) =>
    new Date(timestamp).toLocaleTimeString([], {
      hour: "2-digit",
      minute: "2-digit",
    });

  return (
    <>
      <div className="fx-chart-summary">
        <span>
          Lowest <strong>{formatAxis(lowestRate)}</strong>
        </span>

        <span>
          Highest <strong>{formatAxis(highestRate)}</strong>
        </span>

        <span>
          Observed range{" "}
          <strong>{observedRange.toFixed(10)}</strong>
        </span>
      </div>

      {unchanged && (
        <div className="fx-notice">
          All observations in this selection have the same rate:{" "}
          <strong>{points[0].marketRate}</strong>.
          {" "}The flat line represents unchanged recorded prices.
        </div>
      )}

      <svg
        className="fx-chart"
        viewBox="0 0 1000 300"
        role="img"
        aria-label={
          unchanged
            ? `EUR/USD remained unchanged at ${points[0].marketRate}.`
            : `EUR/USD ranged from ${formatAxis(lowestRate)} to ${formatAxis(highestRate)}.`
        }
      >
        {Array.from({ length: tickCount }, (_, index) => {
          const fraction = index / (tickCount - 1);
          const value = maximum - fraction * (maximum - minimum);
          const position = top + fraction * (bottom - top);

          return (
            <g key={index}>
              <line
                x1={left}
                x2={right}
                y1={position}
                y2={position}
                stroke="#e8e4eb"
                strokeDasharray="4 4"
              />

              <text
                x={left - 16}
                y={position + 4}
                textAnchor="end"
              >
                {formatAxis(value)}
              </text>
            </g>
          );
        })}

        <polyline
          fill="none"
          stroke="#501B65"
          strokeWidth="2.5"
          strokeLinejoin="round"
          strokeLinecap="round"
          points={trusted
            .map(
              (point) =>
                `${x(Date.parse(point.observationTime))},${y(
                  Number(point.marketRate),
                )}`,
            )
            .join(" ")}
        />

        {points.map((point) => (
          <circle
            key={point.snapshotId}
            cx={x(Date.parse(point.observationTime))}
            cy={y(Number(point.marketRate))}
            r={point.validationStatus === "TRUSTED" ? 3.5 : 5}
            fill={colorFor(point.validationStatus)}
          >
            <title>
              {formatTime(point.observationTime)}
              {"\n"}
              Rate: {point.marketRate}
              {"\n"}
              Status: {point.validationStatus}
            </title>
          </circle>
        ))}

        <text x={left} y="275">
          {timeLabel(firstTime)}
        </text>

        {firstTime !== lastTime && (
          <text
            x={right}
            y="275"
            textAnchor="end"
          >
            {timeLabel(lastTime)}
          </text>
        )}
      </svg>

      <div className="fx-legend">
        <span>● Trusted</span>

        <span style={{ color: "#b45309" }}>
          ● Suspicious
        </span>

        <span style={{ color: "#dc2626" }}>
          ● Invalid
        </span>
      </div>

      <p className="fx-muted">
        Y-axis automatically fits the observed price range and does not
        start at zero. Provider observation times use your local timezone.
        Classifications reflect current review decisions.
      </p>
    </>
  );
}