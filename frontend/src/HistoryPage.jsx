import { useEffect, useState } from 'react'
import { loadHistory } from './history-api.js'
import { historyAmount, historyDate, historyType, receiptUrl } from './history-format.js'
import './history.css'

export default function HistoryPage({ dashboard, accountLoading, accountError, refreshAccount }) {
  const [walletId, setWalletId] = useState('')
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(20)
  const [result, setResult] = useState(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [refresh, setRefresh] = useState(0)

  useEffect(() => {
    if (!dashboard || accountError) return
    let current = true
    setLoading(true)
    setResult(null)
    setError('')
    loadHistory({ walletId, page, size }).then((data) => {
      if (current) setResult(data)
    }).catch((failure) => {
      if (!current) return
      setError(failure.status === 401
        ? 'Your session has expired. Sign in again to view history.'
        : failure.status === 404
          ? 'That wallet is not available in your account. Choose another filter.'
          : 'Transaction history could not be retrieved. No balances or entries have been substituted. Try refreshing.')
    }).finally(() => { if (current) setLoading(false) })
    return () => { current = false }
  }, [dashboard, accountError, walletId, page, size, refresh])

  const busy = accountLoading || loading
  return (
    <div className="fx-dashboard">
      <header className="dashboard-header"><a className="dashboard-brand" href="/dashboard/">FXLock</a><span>Transaction history</span></header>
      <main className="dashboard-main">
        <p className="dashboard-eyebrow">YOUR WALLET LEDGER</p>
        <h1>Transaction history</h1>
        <nav className="dashboard-actions" aria-label="History navigation">
          <a className="dashboard-button secondary" href="/dashboard/">Back to dashboard</a>
          {dashboard && <a className="dashboard-button secondary" href={dashboard.quoteUrl}>Get a quote</a>}
        </nav>
        <p className="dashboard-hint">Each row is a movement in one of your own wallets. EUR and USD balances remain separate. Deposits and withdrawals are simulated funding.</p>
        {accountLoading && <p role="status">Loading your account…</p>}
        {accountError && <div role="alert" className="dashboard-error"><p>{accountError}</p>
          <button type="button" onClick={refreshAccount} disabled={accountLoading}>Refresh account</button> <a href="/dashboard/login">Sign in</a></div>}
        {dashboard && !accountError && <section className="dashboard-card history-panel" aria-label="Wallet transactions">
          <div className="history-controls">
            <div><label htmlFor="history-wallet">Wallet</label>
              <select id="history-wallet" value={walletId} onChange={(event) => { setWalletId(event.target.value); setPage(0) }}>
                <option value="">All my wallets</option>
                {dashboard.wallets.map((wallet) => <option key={wallet.walletId} value={wallet.walletId}>
                  {wallet.currency} wallet #{wallet.walletId} ({wallet.status})
                </option>)}
              </select>
            </div>
            <div><label htmlFor="history-size">Entries per page</label>
              <select id="history-size" value={size} onChange={(event) => { setSize(Number(event.target.value)); setPage(0) }}>
                {[20, 50, 100].map((value) => <option key={value} value={value}>{value}</option>)}
              </select>
            </div>
            <button type="button" className="secondary" disabled={busy} onClick={() => setRefresh((value) => value + 1)}>Refresh history</button>
          </div>
          {loading && <p role="status">Loading transactions…</p>}
          {error && <p role="alert" className="dashboard-error">{error}</p>}
          {!loading && result && <>
            {result.entries.length === 0 ? <p role="status">No transactions found for this selection.</p> : <div className="history-scroll" tabIndex="0" aria-label="Scrollable transaction ledger">
              <table className="history-table">
                <caption>Your wallet movements, newest first. Amounts are shown in the currency of each row.</caption>
                <thead><tr><th scope="col">Date and time</th><th scope="col">Type</th><th scope="col">Transaction reference</th>
                  <th scope="col">Wallet / currency</th><th scope="col">Debit</th><th scope="col">Credit</th><th scope="col">Balance after</th></tr></thead>
                <tbody>{result.entries.map((entry) => <tr key={entry.entryId}>
                  <td><time dateTime={entry.transactionDate}>{historyDate(entry.transactionDate)}</time></td>
                  <td>{historyType(entry.transactionType)}</td>
                  <td className="history-reference">{entry.settlementId
                    ? <a aria-label={`Settlement details ${entry.transactionRef}`} href={receiptUrl(dashboard.quoteUrl, entry.settlementId)}>{entry.transactionRef}</a>
                    : entry.transactionRef}</td>
                  <td>#{entry.walletId} · {entry.currency}</td>
                  <td className="history-amount">{historyAmount(entry.amountDebited)} {entry.currency}</td>
                  <td className="history-amount">{historyAmount(entry.amountCredited)} {entry.currency}</td>
                  <td className="history-amount">{historyAmount(entry.updatedBalance)} {entry.currency}</td>
                </tr>)}</tbody>
              </table>
            </div>}
            <nav className="dashboard-actions" aria-label="History pagination">
              <button className="secondary" type="button" disabled={busy || page === 0} onClick={() => setPage((value) => value - 1)}>Previous</button>
              <span role="status">Page {page + 1} · {result.total} wallet entries</span>
              <button className="secondary" type="button" disabled={busy || !result.hasNext} onClick={() => setPage((value) => value + 1)}>Next</button>
            </nav>
          </>}
        </section>}
      </main>
    </div>
  )
}
