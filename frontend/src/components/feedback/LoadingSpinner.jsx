import { LoaderCircle } from "lucide-react";
export default function LoadingSpinner({ label = "Loading your workspace…" }) {
  return (
    <div role="status" className="loading">
      <LoaderCircle className="spin" size={24} />
      <span>{label}</span>
    </div>
  );
}
