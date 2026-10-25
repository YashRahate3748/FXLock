import { useEffect, useState } from 'react'
import { loadDashboard, logout } from './api.js'
import { completionMessage, eligibleWallets, messageFor, readIntent, recoveryKey, saveIntent } from './funding.js'
import FundingDialog from './FundingDialog.jsx'
import HistoryPage from './HistoryPage.jsx'

export default function App() {
  const [data, setData] = useState(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [message, setMessage] = useState('')
  const [operation, setOperation] = useState(null)
  const [pending, setPending] = useState(null)
  const [loggingOut, setLoggingOut] = useState(false)

  async function refresh() {
    setLoading(true)
    setError('')
    try {
      const next = await loadDashboard()
      setData(next)
      setPending(readIntent(next.account.customerRef))
    } catch (failure) {
      setError(failure.status === 401 ? messageFor(failure) : 'Account information could not be retrieved. Previously displayed balances may be out of date.')
    } finally { setLoading(false) }
  }

  useEffect(() => { refresh() }, [])

  function remember(intent) {
    if (intent) saveIntent(data.account.customerRef, intent)
    else sessionStorage.removeItem(recoveryKey(data.account.customerRef))
    setPending(intent)
  }

  async function completed(result) {
    setMessage(completionMessage(result))
    remember(null)
    await refresh()
  }

  async function signOut() {
    if (loggingOut) return
    setLoggingOut(true)
    try {
      const result = await logout(data.csrfToken)
      window.location.assign(result.logoutUrl)
    } catch {
      setError('Logout could not be completed. Continue to the quote service to finish logging out of both sessions.')
      window.location.assign(`${new URL(data.quoteUrl).origin}/dashboard/logout`)
    }
  }

  if (['/dashboard/history', '/dashboard/history/'].includes(window.location.pathname)) {
    return <HistoryPage dashboard={data} accountLoading={loading} accountError={error} refreshAccount={refresh} />
  }

  return (
    <div className="fx-dashboard">
      <header className="dashboard-header"><a className="dashboard-brand" href="/dashboard/">FXLock</a><span>Customer dashboard</span></header>
      <main className="dashboard-main">
        <p className="dashboard-eyebrow">YOUR ACCOUNT</p>
        <h1>{data ? `Welcome, ${data.account.fullName}` : 'Customer dashboard'}</h1>
        {loading && <p role="status">Loading account information…</p>}
        {error && <div className="dashboard-error" role="alert"><p>{error}</p><button onClick={refresh} disabled={loading}>Refresh</button> <a href="http://localhost:8082/login">Sign in</a></div>}
        {message && <p className="dashboard-success" role="status">{message}</p>}
        {data && <>
          <section className="dashboard-card" aria-labelledby="account-title">
            <h2 id="account-title">Account details</h2>
            <dl className="dashboard-details">
              <div><dt>Full name</dt><dd>{data.account.fullName}</dd></div>
              <div><dt>Email</dt><dd>{data.account.email}</dd></div>
              <div><dt>Customer reference</dt><dd>{data.account.customerRef}</dd></div>
              <div><dt>Account status</dt><dd>{data.account.accountStatus}</dd></div>
              {data.account.registeredAt && <div><dt>Registered</dt><dd>{new Date(data.account.registeredAt).toLocaleDateString()}</dd></div>}
            </dl>
          </section>
          <section className="dashboard-card" aria-labelledby="wallet-title">
            <h2 id="wallet-title">Your wallets</h2>
            {data.wallets.length === 0 ? <p>You have no wallets yet.</p> : <div className="dashboard-wallets">
              {data.wallets.map((wallet) => <article className="dashboard-wallet" key={wallet.walletId}>
                <h3>{wallet.currency}</h3><p className="dashboard-balance">{wallet.balance}</p><p>{wallet.status}</p>
              </article>)}
            </div>}
          </section>
          {pending && <aside className="dashboard-notice"><p>A funding request needs an outcome check. Its original intent has been retained.</p>
            <button onClick={() => setOperation(pending.operation)}>Recover funding request</button></aside>}
          <nav className="dashboard-actions" aria-label="Account actions">
            <button disabled={loading || Boolean(error) || Boolean(pending) || !eligibleWallets(data.wallets).length} onClick={() => setOperation('DEPOSIT')}>Deposit money</button>
            <button disabled={loading || Boolean(error) || Boolean(pending) || !eligibleWallets(data.wallets).length} onClick={() => setOperation('WITHDRAWAL')}>Withdraw money</button>
            <a className="dashboard-button secondary" href={data.quoteUrl}>Get a quote</a>
            <a className="dashboard-button secondary" href="/dashboard/history/">Transaction history</a>
            <button className="secondary" onClick={signOut} disabled={loggingOut}>Logout</button>
          </nav>
          {operation && <FundingDialog operation={operation} wallets={data.wallets} csrf={data.csrfToken} pending={pending}
            remember={remember} completed={completed} close={() => setOperation(null)} />}
        </>}
      </main>
    </div>
  )
}
