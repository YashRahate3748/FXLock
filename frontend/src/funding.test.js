import test from 'node:test'
import assert from 'node:assert/strict'
import { ApiError, completeFunding, loadDashboard } from './api.js'
import { eligibleWallets, validAmount, messageFor, saveIntent, readIntent, completionMessage } from './funding.js'

test('eligible wallets exclude inactive and unsupported currencies and preserve zero', () => {
  const wallets = [
    { walletId: '1', currency: 'EUR', balance: '0.0000', status: 'ACTIVE' },
    { walletId: '2', currency: 'USD', balance: '20.0000', status: 'BLOCKED' },
    { walletId: '3', currency: 'GBP', balance: '12', status: 'ACTIVE' },
  ]
  assert.deepEqual(eligibleWallets(wallets), [wallets[0]])
})

test('amount validation preserves strings and rejects invalid precision', () => {
  for (const value of ['1', '0.01', '999999999999999.99']) assert.equal(validAmount(value), true)
  for (const value of ['0', '0.00', '-1', '1.001', '1e3', 'NaN', ' 1', '1,000', '.1']) assert.equal(validAmount(value), false)
})

test('recovery storage explicitly excludes credentials', () => {
  const values = new Map()
  globalThis.sessionStorage = { setItem: (k, v) => values.set(k, v), getItem: (k) => values.get(k) }
  const intent = { key: 'original-intent', walletId: '1', operation: 'DEPOSIT', amount: '2.00', password: 'never-save-this' }
  saveIntent('fixture', intent)
  assert.doesNotMatch([...values.values()].join(), /password|never-save-this/)
  assert.equal(readIntent('fixture').key, 'original-intent')
  assert.equal(readIntent('another-customer'), null)
})

test('rejection differs from uncertain response and completion names actual result', () => {
  assert.match(messageFor(new ApiError('CONFIRMATION_REJECTED', 401)), /incorrect/)
  assert.match(messageFor(new ApiError('INTERNAL_ERROR', 500)), /uncertain/)
  assert.match(messageFor(new ApiError('CONNECTION_UNCERTAIN')), /original request key/)
  assert.match(completionMessage({ operation: 'WITHDRAWAL', amount: '2.00', currency: 'EUR', transactionRef: 'ref' }), /Withdrawal completed: 2.00 EUR.*ref/)
})

test('submission transmits exact password, decimal string, original key and CSRF', async () => {
  globalThis.fetch = async (url, options) => {
    assert.equal(url, '/api/dashboard/funding')
    assert.equal(options.headers['Idempotency-Key'], 'original-key')
    assert.equal(options.headers['X-CSRF-TOKEN'], 'csrf')
    const body = JSON.parse(options.body)
    assert.equal(body.password, ' exact password ')
    assert.equal(body.amount, '0.10')
    assert.equal(body.customerRef, undefined)
    return { ok: true, json: async () => ({ transactionRef: 'committed', operation: 'DEPOSIT', walletId: '1', amount: '0.10', currency: 'EUR', idempotencyKey: 'original-key' }) }
  }
  const result = await completeFunding({ key: 'original-key', operation: 'DEPOSIT', walletId: '1', amount: '0.10' }, ' exact password ', 'csrf')
  assert.equal(result.transactionRef, 'committed')
})

test('data failure never becomes a fabricated zero balance', async () => {
  globalThis.fetch = async () => ({ ok: false, status: 503, json: async () => ({ code: 'UNAVAILABLE' }) })
  await assert.rejects(loadDashboard(), (error) => error.status === 503 && error.uncertain)
  globalThis.fetch = async () => { throw new Error('connection lost') }
  await assert.rejects(loadDashboard(), (error) => error.code === 'CONNECTION_UNCERTAIN')
})

test('timeouts and malformed success responses preserve an uncertain mutation outcome', async () => {
  assert.equal(new ApiError('TIMEOUT', 408).uncertain, true)
  globalThis.fetch = async () => ({ ok: true, json: async () => ({}) })
  await assert.rejects(completeFunding({ key: 'original-key', operation: 'DEPOSIT', walletId: '1', amount: '1.00' }, 'x', 'csrf'),
    (error) => error.code === 'INVALID_RESPONSE' && error.uncertain)
  await assert.rejects(loadDashboard(), (error) => error.code === 'INVALID_RESPONSE')
})
