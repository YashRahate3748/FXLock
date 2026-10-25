export const historyType = (type) => ({
  FX_DEBIT: 'FX debit', FX_CREDIT: 'FX credit', DEPOSIT: 'Simulated deposit', WITHDRAWAL: 'Simulated withdrawal',
})[type] || 'Unavailable'

// Format the server's decimal strings without converting financial values to floating point.
export function historyAmount(value) {
  if (typeof value !== 'string' || !/^\d+(\.\d{1,4})?$/.test(value)) return 'Unavailable'
  const [whole, fraction = ''] = value.split('.')
  return `${whole.replace(/\B(?=(\d{3})+(?!\d))/g, ',')}.${fraction.padEnd(2, '0')}`
}

export function historyDate(value) {
  const date = new Date(value)
  if (!value || Number.isNaN(date.valueOf())) return 'Unavailable'
  return date.toLocaleString(undefined, {
    year: 'numeric', month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit', second: '2-digit', timeZoneName: 'short',
  })
}

export function receiptUrl(quoteUrl, settlementId) {
  const url = new URL('/settlement.html', quoteUrl)
  url.searchParams.set('settlementId', settlementId)
  return url.toString()
}
