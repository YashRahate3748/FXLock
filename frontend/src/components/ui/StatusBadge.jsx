export default function StatusBadge({ status }) {
  return (
    <span className={`badge badge-${String(status).toLowerCase()}`}>
      <span />
      {String(status).replaceAll("_", " ")}
    </span>
  );
}
