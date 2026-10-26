// Convert only identifiers and decimal fields before JSON.parse can round them.
export function parseBackendJson(text) {
  if (typeof text !== "string") return text;
  const keys = "walletId|customerId|ledgerId|balance|sourceAmount|targetAmount|marketRateWhenQuoted|lockedCustomerRate|appliedSpreadBps|amount|balanceBefore|balanceAfter|currentWalletBalance";
  const token = new RegExp('("(?:' + keys + ')"\\s*:\\s*)(-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?)', 'g');
  return JSON.parse(text.replace(token, (_, key, number) => key + JSON.stringify(expandDecimal(number))));
}
export function expandDecimal(value) {
  const text = String(value);
  if (!/[eE]/.test(text)) return text;
  const match = /^(-?)(\d+)(?:\.(\d+))?[eE]([+-]?\d+)$/.exec(text);
  if (!match) throw new Error("Invalid decimal response.");
  const [, sign, whole, fraction = "", exponent] = match;
  const digits = whole + fraction, position = whole.length + Number(exponent);
  if (Math.abs(position) > 1000) throw new Error("Decimal response is outside supported precision.");
  return sign + (position <= 0 ? "0." + "0".repeat(-position) + digits : position >= digits.length ? digits + "0".repeat(position - digits.length) : digits.slice(0,position) + "." + digits.slice(position));
}
export function filterHistory(entries, {search = "", currency = "", start, end} = {}) {
  if (start && !Number.isFinite(Date.parse(start)) || end && !Number.isFinite(Date.parse(end))) throw new Error("Choose valid dates.");
  if (start && end && Date.parse(start) >= Date.parse(end)) throw new Error("The start date must not be after the end date.");
  const term = search.trim().toLowerCase();
  return entries.filter((entry) => (!currency || entry.currency === currency)
    && (!term || [entry.customerName, entry.settlementId, entry.transactionRef].some((v) => String(v || "").toLowerCase().includes(term)))
    && (!start || Date.parse(entry.transactionDate) >= Date.parse(start))
    && (!end || Date.parse(entry.transactionDate) < Date.parse(end)));
}
export async function readHistory(client, options = {}, signal) {
  const {walletId, page = 0, size = 10, search, currency, start, end} = options;
  const filtered = Boolean(search || currency || start || end);
  if (!filtered) return (await client.get("/api/transaction-history", {params: {walletId, page, size}, signal})).data;
  // The existing backend supports wallet/page only. Read every page before filtering;
  // never present a filtered single page as a complete set of search results.
  let all = [], current = 0;
  while (true) {
    const {data} = await client.get("/api/transaction-history", {params: {walletId, page: current, size: 100}, signal});
    all.push(...data.entries);
    if (!data.hasNext) break;
    if (++current >= 100) throw new Error("This history is too large to filter in the browser. Select an individual wallet or clear the extra filters.");
  }
  const unique = [...new Map(all.map((e) => [e.entryId || e.ledgerId, e])).values()];
  const matches = filterHistory(unique, options);
  return {entries: matches.slice(page * size, (page + 1) * size), total: matches.length, page, size, hasNext: (page + 1) * size < matches.length};
}
export function receiptView(details) {
  const debit = details.ledgerEntries?.find((entry) => entry.entryType === "DEBIT");
  const credit = details.ledgerEntries?.find((entry) => entry.entryType === "CREDIT");
  return {...details, STATUS: details.status, SOURCE_AMOUNT: details.sourceAmount, TARGET_AMOUNT: details.targetAmount,
    SOURCE_CURRENCY: details.sourceCurrency, TARGET_CURRENCY: details.targetCurrency, CUSTOMER_RATE: details.lockedCustomerRate,
    SOURCE_WALLET_ID: debit?.walletId ?? "Not included in this receipt", TARGET_WALLET_ID: credit?.walletId ?? "Not included in this receipt",
    COMPLETED_AT: details.completedAt, SETTLEMENT_ID: details.settlementId, QUOTE_ID: details.quoteId};
}
