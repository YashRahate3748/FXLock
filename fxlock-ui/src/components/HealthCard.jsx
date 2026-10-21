function HealthCard({
  health,
  loading,
  onRefresh,
}) {
  return (
    <section className="card">
      <div className="card-header">
        <h2>Application Health</h2>

        <button
          onClick={onRefresh}
          disabled={loading}
        >
          Refresh
        </button>
      </div>

      {loading ? (
        <p>Checking...</p>
      ) : (
        <div className="status-row">
          <span>Status</span>

          <span
            className={
              health === "UP"
                ? "badge success"
                : "badge danger"
            }
          >
            {health || "UNKNOWN"}
          </span>
        </div>
      )}
    </section>
  );
}

export default HealthCard;