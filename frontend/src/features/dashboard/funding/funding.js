export const eligibleWallets = (wallets) => wallets.filter((wallet) => wallet.status === "ACTIVE" && ["EUR", "USD"].includes(wallet.currency));
export const validFundingAmount = (amount) => /^\d{1,15}(\.\d{1,2})?$/.test(amount) && /[1-9]/.test(amount);
export const recoveryKey = (customerRef) => "fxlock.funding." + customerRef;
export function readFundingIntent(customerRef, storage = sessionStorage) {
  try {
    const value = JSON.parse(storage.getItem(recoveryKey(customerRef)));
    if (value && ["DEPOSIT","WITHDRAWAL"].includes(value.operation) && typeof value.key === "string"
      && /^[A-Za-z0-9_-]{16,100}$/.test(value.key) && typeof value.walletId === "string" && /^[1-9][0-9]{0,17}$/.test(value.walletId)
      && typeof value.amount === "string" && validFundingAmount(value.amount))
      return {operation:value.operation,key:value.key,walletId:value.walletId,amount:value.amount,currency:value.currency};
  } catch { /* Storage can be unavailable. Submission will require a successful save. */ }
  return null;
}
export function saveFundingIntent(customerRef, intent, storage = sessionStorage) {
  if (!intent) {storage.removeItem(recoveryKey(customerRef));return;}
  // Explicit allowlist: no password or token may enter browser storage.
  const {operation,key,walletId,amount,currency} = intent;
  storage.setItem(recoveryKey(customerRef),JSON.stringify({operation,key,walletId,amount,currency}));
}
export class FundingError extends Error {
  constructor(code,status=0) {super(code);this.code=code;this.status=status;this.uncertain=status===0 || status===408 || status>=500 || code==="FUNDING_PENDING";}
}
export function fundingMessage(error) {
  const messages={
    CONFIRMATION_REJECTED:"The current login password is incorrect or missing. Enter it again.",
    PASSWORD_THROTTLED:"Too many incorrect passwords. Wait before confirming again.",
    INSUFFICIENT_FUNDS:"This wallet has insufficient funds. No withdrawal was made.",
    INVALID_AMOUNT:"Enter a positive amount with at most two decimal places.",
    AMOUNT_LIMIT:"The amount must be positive and within the configured funding limit.",
    BALANCE_LIMIT:"This deposit would exceed the wallet balance limit.",
    WALLET_UNAVAILABLE:"The selected wallet is unavailable for this operation.",
    ACCOUNT_NOT_ACTIVE:"Your customer account is no longer active.",
    AUTHENTICATION_REQUIRED:"Sign in with your registered email and password to manage wallet funds.",
    CSRF_REJECTED:"Your confirmation session has expired. Close and reopen this dialog before trying again.",
    IDEMPOTENCY_CONFLICT:"This request key belongs to a different intent. Check the outcome before making another request.",
    IDENTITY_MISMATCH:"Your wallet session does not match this customer. Sign in again before managing funds.",
  };
  if(error.uncertain) return "The outcome is uncertain. Check the outcome before making another request. Any retry keeps the original wallet, amount and request key.";
  return messages[error.code] || "The request was rejected. No funding change was confirmed.";
}
const decimal = (value) => {const [whole,fraction=""] = value.split(".");return whole.replace(/^0+(?=\d)/,"")+"."+fraction.padEnd(2,"0");};
export function confirmFundingResult(result,intent) {
  if(!result || typeof result.transactionRef!=="string" || !result.transactionRef || result.idempotencyKey!==intent.key
    || result.operation!==intent.operation || result.walletId!==intent.walletId || !["EUR","USD"].includes(result.currency)
    || intent.currency && result.currency!==intent.currency || typeof result.amount!=="string" || !validFundingAmount(result.amount)
    || decimal(result.amount)!==decimal(intent.amount) || typeof result.balanceAfter!=="string" || !/^\d+(\.\d{1,4})?$/.test(result.balanceAfter))
    throw new FundingError("INVALID_RESPONSE");
  return result;
}
