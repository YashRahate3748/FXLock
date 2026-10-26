import { LoaderCircle } from "lucide-react";
export default function Button({
  children,
  variant = "primary",
  busy = false,
  className = "",
  disabled,
  ...props
}) {
  return (
    <button
      className={`button button-${variant} ${className}`}
      disabled={disabled || busy}
      {...props}
    >
      {busy && <LoaderCircle size={17} className="spin" />}
      {children}
    </button>
  );
}
