import test from 'node:test'
import assert from 'node:assert/strict'
import { loadHistory } from './history-api.js'
import { historyAmount, historyDate, historyType, receiptUrl } from './history-format.js'

const entry = {
  entryId: 'FX:17', ledgerId: '17', settlementId: 'settlement', walletId: '101', currency: 'USD',
  transactionType: 'FX_CREDIT', transactionRef: 'settlement', transactionDate: '2026-10-24T10:00:00Z',
  amountCredited: '25.0000', amountDebited: '0', updatedBalance: '25.0000',
}

test('ledger formatting preserves precision, large values and genuine zero without currency totals', () => {
  assert.equal(historyAmount('999999999999999.9999'), '999,999,999,999,999.9999')
  assert.equal(historyAmount('0'), '0.00')
  assert.equal(historyAmount('0.0000'), '0.0000')
  assert.equal(historyAmount(null), 'Unavailable')
  assert.equal(historyAmount('NaN'), 'Unavailable')
  assert.equal(historyDate('bad date'), 'Unavailable')
  assert.equal(historyType('DEPOSIT'), 'Simulated deposit')
  assert.equal(historyType('FX_CREDIT'), 'FX credit')
})

test('history requests encode filters/pagination and never supply customer identity', async () => {
  globalThis.fetch = async (url, options) => {
    assert.equal(url, '/api/dashboard/history?page=2&size=50&walletId=101')
    assert.equal(options.credentials, 'same-origin')
    assert.equal(options.body, undefined)
    return { ok: true, json: async () => ({ entries: [entry], page: 2, size: 50, total: 101, hasNext: false }) }
  }
  const page = await loadHistory({ walletId: '101', page: 2, size: 50 })
  assert.equal(page.entries[0].amountDebited, '0')
})

test('empty history differs from a failed or malformed response', async () => {
  globalThis.fetch = async () => ({ ok: true, json: async () => ({ entries: [], page: 0, size: 20, total: 0, hasNext: false }) })
  assert.equal((await loadHistory()).total, 0)
  globalThis.fetch = async () => ({ ok: false, status: 503, json: async () => ({ code: 'HISTORY_UNAVAILABLE' }) })
  await assert.rejects(loadHistory(), (error) => error.status === 503)
  globalThis.fetch = async () => ({ ok: true, json: async () => ({ entries: [{ ...entry, updatedBalance: null }], page: 0, size: 20, total: 1, hasNext: false }) })
  await assert.rejects(loadHistory(), (error) => error.code === 'INVALID_RESPONSE')
})

test('FX references navigate to the preserved M2 receipt route', () => {
  assert.equal(receiptUrl('http://localhost:8082/', 'actual-ref'), 'http://localhost:8082/settlement.html?settlementId=actual-ref')
})
