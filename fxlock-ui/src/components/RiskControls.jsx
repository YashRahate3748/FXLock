function RiskControls({
  loading,
  onEnable,
  onDisable,
}) {
  return (
    <section className="card">
      <h2>Risk Controls</h2>

      <p className="muted">
        Requires RISK_ANALYST credentials.
      </p>

      <div className="button-row">

        <button
          className="success-button"
          onClick={onEnable}
          disabled={loading}
        >
          Enable Quoting
        </button>

        <button
          className="danger-button"
          onClick={onDisable}
          disabled={loading}
        >
          Disable Quoting
        </button>

      </div>
    </section>
  );
}

export default RiskControls;