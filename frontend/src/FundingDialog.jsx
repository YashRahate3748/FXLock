import { useEffect, useRef, useState } from 'react'
import { checkOutcome, completeFunding } from './api.js'
import { completionMessage, eligibleWallets, messageFor, validAmount } from './funding.js'

export default function FundingDialog({ operation, wallets, csrf, pending, remember, completed, close }) {
  const dialog = useRef(null)
  const password = useRef(null)
  const walletSelect = useRef(null)
  const inFlight = useRef(false)
  const active = eligibleWallets(wallets)
  const [walletId, setWalletId] = useState(pending?.walletId || active[0]?.walletId || '')
  const [amount, setAmount] = useState(pending?.amount || '')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const selected = wallets.find((wallet) => wallet.walletId === walletId)
  const title = operation === 'DEPOSIT' ? 'Deposit money' : 'Withdraw money'

  useEffect(() => {
    const opener = document.activeElement
    dialog.current.showModal()
    walletSelect.current?.focus()
    return () => { dialog.current?.close(); opener?.focus() }
  }, [])

  const cancel = () => {
    if (inFlight.current) return
    password.current.value = ''
    close()
  }

  async function submit(event) {
    event.preventDefault()
    if (inFlight.current) return
    if (!validAmount(amount)) { setError('Enter a positive amount with at most two decimal places.'); password.current.value = ''; return }
    const intent = pending || { operation, walletId, amount, key: crypto.randomUUID() }
    try {
      remember(intent)
    } catch {
      setError('Recovery storage is unavailable. Enable session storage before submitting. No request was sent.')
      password.current.value = ''
      return
    }
    inFlight.current = true
    setBusy(true)
    setError('')
    setNotice('')
    const confirmation = password.current.value
    password.current.value = ''
    try {
      const result = await completeFunding(intent, confirmation, csrf)
      await completed(result)
      close()
    } catch (failure) {
      setError(messageFor(failure))
      // A confirmed rejection permits editing; uncertain outcomes retain the exact intent.
      if (!failure.uncertain) remember(null)
    } finally {
      inFlight.current = false
      setBusy(false)
      if (password.current) password.current.value = ''
    }
  }

  async function check() {
    if (inFlight.current || !pending) return
    inFlight.current = true
    setBusy(true)
    setError('')
    password.current.value = ''
    try {
      const result = await checkOutcome(pending.key)
      setNotice(completionMessage(result))
      await completed(result)
      close()
    } catch (failure) {
      setError(failure.status === 404
        ? 'No committed result is visible yet. A request may still be processing. Recheck, or re-enter your password and Complete using the same request key.'
        : messageFor(failure))
    } finally { inFlight.current = false; setBusy(false) }
  }

  return (
    <dialog ref={dialog} className="funding-dialog" aria-labelledby="funding-title" aria-describedby="funding-scope"
      onCancel={(event) => { event.preventDefault(); cancel() }}>
      <form onSubmit={submit}>
        <h2 id="funding-title">{title}</h2>
        <p id="funding-scope" className="dashboard-hint">Simulated wallet funding only. No bank, card or payment provider is connected. No external money is collected or paid out.</p>
        <label htmlFor="funding-wallet">Wallet</label>
        <select id="funding-wallet" ref={walletSelect} value={walletId} required disabled={busy || Boolean(pending)}
          onChange={(event) => setWalletId(event.target.value)}>
          {!active.length && <option value="">No eligible active wallets</option>}
          {active.map((wallet) => <option key={wallet.walletId} value={wallet.walletId}>{wallet.currency} — balance {wallet.balance}</option>)}
          {pending && !active.some((wallet) => wallet.walletId === pending.walletId) && <option value={pending.walletId}>Previously selected wallet</option>}
        </select>
        <label htmlFor="funding-amount">Amount <span aria-live="polite">{selected?.currency || ''}</span></label>
        <input id="funding-amount" value={amount} onChange={(event) => setAmount(event.target.value)} inputMode="decimal"
          required pattern="[0-9]{1,15}(\.[0-9]{1,2})?" disabled={busy || Boolean(pending)} aria-describedby="amount-hint" />
        <p id="amount-hint" className="dashboard-hint">Use at most two decimal places. No currency conversion.</p>
        <label htmlFor="funding-password">Current login password</label>
        <input id="funding-password" ref={password} type="password" autoComplete="off" required disabled={busy} />
        {pending && <p className="dashboard-hint">Recovering request {pending.key}. Its wallet, amount and operation are preserved.</p>}
        {error && <p role="alert" className="dashboard-error">{error}</p>}
        {notice && <p role="status">{notice}</p>}
        {busy && <p role="status">Processing. Please wait; a submitted transaction cannot be cancelled.</p>}
        <div className="dashboard-actions">
          <button type="submit" disabled={busy || (!active.length && !pending)}>Complete</button>
          <button type="button" className="secondary" onClick={cancel} disabled={busy}>Cancel</button>
          {pending && <button type="button" className="secondary" disabled={busy} onClick={check}>Check outcome</button>}
        </div>
      </form>
    </dialog>
  )
}
