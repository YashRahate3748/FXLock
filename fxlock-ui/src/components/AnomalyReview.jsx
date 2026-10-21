import { useState } from "react";

function AnomalyReview({
  loading,
  onApprove,
  onReject,
}) {

  const [anomalyId, setAnomalyId] =
    useState("");

  const [reason, setReason] =
    useState("");

  const handleApprove = () => {

    if (!validate()) {
      return;
    }

    onApprove(
      anomalyId,
      reason
    );
  };

  const handleReject = () => {

    if (!validate()) {
      return;
    }

    onReject(
      anomalyId,
      reason
    );
  };

  const validate = () => {

    if (!anomalyId.trim()) {
      alert(
        "Enter anomaly ID."
      );
      return false;
    }

    if (!reason.trim()) {
      alert(
        "Enter review reason."
      );
      return false;
    }

    return true;
  };

  return (
    <section className="card">

      <h2>Anomaly Review</h2>

      <p className="muted">
        Requires RISK_ANALYST credentials.
      </p>

      <div className="form-group">

        <label>
          Anomaly ID
        </label>

        <input
          type="number"
          value={anomalyId}
          onChange={(event) =>
            setAnomalyId(
              event.target.value
            )
          }
          placeholder="Example: 411"
        />

      </div>

      <div className="form-group">

        <label>
          Review Reason
        </label>

        <textarea
          rows="3"
          value={reason}
          onChange={(event) =>
            setReason(
              event.target.value
            )
          }
          placeholder="Reason for approval or rejection"
        />

      </div>

      <div className="button-row">

        <button
          className="success-button"
          onClick={handleApprove}
          disabled={loading}
        >
          Approve
        </button>

        <button
          className="danger-button"
          onClick={handleReject}
          disabled={loading}
        >
          Reject
        </button>

      </div>

    </section>
  );
}

export default AnomalyReview;