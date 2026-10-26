import { AlertCircle } from "lucide-react";
export default function ErrorMessage({ message }) {
  return message ? (
    <div role="alert" className="error-message">
      <AlertCircle size={19} />
      <span>{message}</span>
    </div>
  ) : null;
}
