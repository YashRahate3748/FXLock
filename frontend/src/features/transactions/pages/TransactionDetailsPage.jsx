import { useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { ArrowLeft, ArrowRight, ReceiptText } from "lucide-react";
import { apiClient } from "../../../services/apiClient";
import { endpoints } from "../../../services/endpoints";
import { money, dateTime } from "../../../lib/format";
import Card from "../../../components/ui/Card";
import StatusBadge from "../../../components/ui/StatusBadge";
import LoadingSpinner from "../../../components/feedback/LoadingSpinner";
import ErrorMessage from "../../../components/feedback/ErrorMessage";
export default function TransactionDetailsPage({ result = false }) {
  const { id } = useParams();
  const [data, setData] = useState(null),
    [error, setError] = useState("");
  useEffect(() => {
    let active = true;
    apiClient
      .get(endpoints.settlement(id))
      .then((r) => {
        if (active) setData(r.data);
      })
      .catch((e) => {
        if (active) setError(e.message);
      });
    return () => {
      active = false;
    };
  }, [id]);
  if (error)
    return (
      <>
        <ErrorMessage message={error} />
        <p>
          No completed transfer can be confirmed from this response. Check
          history before retrying.
        </p>
        <Link to="/transactions">View transaction history</Link>
      </>
    );
  if (!data)
    return <LoadingSpinner label="Checking the recorded settlement…" />;
  return (
    <div className="page-enter receipt-page">
      <Link className="back-link" to="/transactions">
        <ArrowLeft size={16} /> Transaction history
      </Link>
      <div className="page-heading">
        <div>
          <p className="eyebrow">YOUR RECORDED RESULT</p>
          <h1>
            {result && data.STATUS === "COMPLETED"
              ? "Your conversion is complete."
              : "Transaction details."}
          </h1>
          <p>Retrieved from your authenticated settlement record.</p>
        </div>
      </div>
      <Card className="receipt-card">
        <div className="receipt-head">
          <span className="empty-icon">
            <ReceiptText size={27} />
          </span>
          <StatusBadge status={data.STATUS} />
        </div>
        <div className="receipt-amounts">
          <div>
            <span>You sent</span>
            <strong>
              {money(data.SOURCE_AMOUNT)} <small>{data.SOURCE_CURRENCY}</small>
            </strong>
          </div>
          <ArrowRight />
          <div>
            <span>You received</span>
            <strong>
              {money(data.TARGET_AMOUNT)} <small>{data.TARGET_CURRENCY}</small>
            </strong>
          </div>
        </div>
        <dl className="detail-list">
          {[
            ["Offered rate", data.CUSTOMER_RATE + " USD per EUR"],
            ["Source wallet", data.SOURCE_WALLET_ID],
            ["Receiving wallet", data.TARGET_WALLET_ID],
            ["Completed", dateTime(data.COMPLETED_AT)],
            ["Settlement reference", data.SETTLEMENT_ID],
            ["Quote reference", data.QUOTE_ID],
          ].map(([key, value]) => (
            <div key={key}>
              <dt>{key}</dt>
              <dd>{value}</dd>
            </div>
          ))}
        </dl>
        <Link className="button button-primary full-width" to="/transactions">
          View transaction history <ArrowRight size={18} />
        </Link>
        <p className="form-assurance">
          Simulated balances and transfers in this learning prototype.
        </p>
      </Card>
    </div>
  );
}
