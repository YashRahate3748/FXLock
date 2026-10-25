export function money(value) {
  const match = /^(-?)(\d+)(?:\.(\d+))?$/.exec(String(value ?? ""));
  if (!match) return "Unavailable";
  let fraction = (match[3] || "").padEnd(2, "0");
  while (fraction.length > 2 && fraction.endsWith("0"))
    fraction = fraction.slice(0, -1);
  return (
    match[1] + match[2].replace(/\B(?=(\d{3})+(?!\d))/g, ",") + "." + fraction
  );
}
export const isZero = (value) => /^0+(?:\.0+)?$/.test(String(value));
export const dateTime = (value) =>
  value
    ? new Date(value).toLocaleString(undefined, {
        day: "2-digit",
        month: "short",
        year: "numeric",
        hour: "2-digit",
        minute: "2-digit",
      })
    : "Not available";
export const shortId = (value) => (value ? value.slice(0, 8) + "…" : "—");
export function validAmount(value, min, max) {
  if (!/^\d+(?:\.\d{1,2})?$/.test(value))
    return "Enter a positive amount with at most two decimal places.";
  const cents = (v) => {
    const [a, b = ""] = String(v).split(".");
    return BigInt(a) * 100n + BigInt((b + "00").slice(0, 2));
  };
  if (cents(value) < cents(min) || cents(value) > cents(max))
    return `Enter an amount between ${money(min)} and ${money(max)} EUR.`;
  return "";
}
