export class ApiError extends Error {
  constructor(code, status = 0) {
    super(code)
    this.code = code
    this.status = status
    this.uncertain = status === 0 || status === 408 || status >= 500 || code === 'FUNDING_PENDING'
  }
}

export async function request(path, options = {}) {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), 15000)
  try {
    const response = await fetch(`/api/dashboard${path}`, {
      credentials: 'same-origin',
      cache: 'no-store',
      ...options,
      signal: controller.signal,
    })
    const body = await response.json().catch(() => ({}))
    if (!response.ok) throw new ApiError(body.code || 'REQUEST_FAILED', response.status)
    return body
  } catch (error) {
    if (error instanceof ApiError) throw error
    throw new ApiError('CONNECTION_UNCERTAIN')
  } finally {
    clearTimeout(timer)
  }
}

export async function loadDashboard() {
  const data = await request('')
  if (!data.account || typeof data.account.customerRef !== 'string' || typeof data.account.fullName !== 'string'
    || !Array.isArray(data.wallets) || typeof data.csrfToken !== 'string' || typeof data.quoteUrl !== 'string'
    || data.wallets.some((wallet) => typeof wallet.walletId !== 'string' || typeof wallet.balance !== 'string'
      || !/^\d+(\.\d{1,4})?$/.test(wallet.balance))) throw new ApiError('INVALID_RESPONSE')
  return data
}

function confirmed(result, key) {
  if (!result || typeof result.transactionRef !== 'string' || !result.transactionRef
    || !['DEPOSIT', 'WITHDRAWAL'].includes(result.operation) || !['EUR', 'USD'].includes(result.currency)
    || typeof result.walletId !== 'string' || typeof result.amount !== 'string'
    || !/^\d+(\.\d{1,2})?$/.test(result.amount) || result.idempotencyKey !== key) throw new ApiError('INVALID_RESPONSE')
  return result
}

export const checkOutcome = async (key) => confirmed(await request(`/funding/${encodeURIComponent(key)}`), key)
export async function completeFunding(intent, password, csrf) {
  const result = confirmed(await request('/funding', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-CSRF-TOKEN': csrf, 'Idempotency-Key': intent.key },
    body: JSON.stringify({ operation: intent.operation, walletId: intent.walletId, amount: intent.amount, password }),
  }), intent.key)
  const decimal = (value) => {
    const [whole, fraction = ''] = value.split('.')
    return `${whole.replace(/^0+(?=\d)/, '')}.${fraction.padEnd(2, '0')}`
  }
  if (result.operation !== intent.operation || result.walletId !== intent.walletId
    || decimal(result.amount) !== decimal(intent.amount)) throw new ApiError('INVALID_RESPONSE')
  return result
}
export const logout = (csrf) => request('/logout', {
  method: 'POST', headers: { 'X-CSRF-TOKEN': csrf },
})
