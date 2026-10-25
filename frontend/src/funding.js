export const eligibleWallets = (wallets) => wallets.filter((wallet) =>
  wallet.status === 'ACTIVE' && ['EUR', 'USD'].includes(wallet.currency))

export const validAmount = (amount) => /^\d{1,15}(\.\d{1,2})?$/.test(amount) && /[1-9]/.test(amount)

export function messageFor(error) {
  const messages = {
    CONFIRMATION_REJECTED: 'The current login password is incorrect or missing. Enter it again.',
    PASSWORD_THROTTLED: 'Too many incorrect passwords. Wait for the confirmation window to expire before trying again.',
    INSUFFICIENT_FUNDS: 'This wallet has insufficient funds. No funding change was made.',
    INVALID_AMOUNT: 'Enter a positive amount with at most two decimal places.',
    AMOUNT_LIMIT: 'The amount must be positive and within the configured funding limit.',
    BALANCE_LIMIT: 'This deposit would exceed the wallet balance limit.',
    WALLET_UNAVAILABLE: 'The selected wallet is unavailable for this operation.',
    ACCOUNT_NOT_ACTIVE: 'Your customer account is no longer active.',
    AUTHENTICATION_REQUIRED: 'Your dashboard session has expired. Sign in again.',
    CSRF_REJECTED: 'Your security token has expired. Refresh the dashboard before submitting again.',
    IDEMPOTENCY_CONFLICT: 'This request key belongs to a different intent. No new funding change was made.',
  }
  if (error.uncertain) return 'The outcome is uncertain. Check the outcome before doing anything else. To resubmit, enter your password again; the original request key will be reused.'
  return messages[error.code] || 'The request was rejected. No funding change was confirmed.'
}

export const completionMessage = (result) =>
  `${result.operation === 'DEPOSIT' ? 'Deposit' : 'Withdrawal'} completed: ${result.amount} ${result.currency}. Transaction reference: ${result.transactionRef}.`

export const recoveryKey = (customerRef) => `fxlock.funding.${customerRef}`
export function readIntent(customerRef) {
  try {
    const value = JSON.parse(sessionStorage.getItem(recoveryKey(customerRef)))
    if (value && ['DEPOSIT', 'WITHDRAWAL'].includes(value.operation) && typeof value.key === 'string'
      && typeof value.walletId === 'string' && typeof value.amount === 'string') {
      return { operation: value.operation, key: value.key, walletId: value.walletId, amount: value.amount }
    }
  } catch { /* Recovery storage may be disabled by the browser. */ }
  return null
}

export function saveIntent(customerRef, intent) {
  // Explicit allowlist: confirmation credentials must never enter browser storage.
  const value = { operation: intent.operation, key: intent.key, walletId: intent.walletId, amount: intent.amount }
  sessionStorage.setItem(recoveryKey(customerRef), JSON.stringify(value))
}
