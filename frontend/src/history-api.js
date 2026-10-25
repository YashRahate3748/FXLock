import { ApiError, request } from './api.js'

const types = new Set(['FX_DEBIT', 'FX_CREDIT', 'DEPOSIT', 'WITHDRAWAL'])
const decimal = /^\d+(\.\d{1,4})?$/

export async function loadHistory({ walletId = '', page = 0, size = 20 } = {}) {
  const query = new URLSearchParams({ page: String(page), size: String(size) })
  if (walletId) query.set('walletId', walletId)
  const result = await request(`/history?${query}`)
  if (!Array.isArray(result.entries) || result.page !== page || result.size !== size
    || !Number.isSafeInteger(result.total) || result.total < 0 || typeof result.hasNext !== 'boolean'
    || result.entries.some((entry) => typeof entry.entryId !== 'string' || typeof entry.walletId !== 'string'
      || !types.has(entry.transactionType) || !['EUR', 'USD'].includes(entry.currency)
      || typeof entry.transactionRef !== 'string' || typeof entry.transactionDate !== 'string'
      || ['amountCredited', 'amountDebited', 'updatedBalance'].some((field) =>
        typeof entry[field] !== 'string' || !decimal.test(entry[field])))) {
    throw new ApiError('INVALID_RESPONSE')
  }
  return result
}
