export default function Input({ label, id, error, hint, ...props }) {
  return (
    <div className="field">
      <label htmlFor={id}>{label}</label>
      <input
        id={id}
        aria-invalid={!!error}
        aria-describedby={error || hint ? `${id}-hint` : undefined}
        {...props}
      />
      {(error || hint) && (
        <p id={`${id}-hint`} className={error ? "field-error" : "hint"}>
          {error || hint}
        </p>
      )}
    </div>
  );
}
