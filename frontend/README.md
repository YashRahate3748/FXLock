# FXLock customer frontend (M2)

React + Vite frontend built on the shared frontend branch at fcad7e0. It follows
UIDoc.pdf, Frontend Guide.pdf and FolderStructure.md: the purple/mint palette,
shared customer layout, feature folders and the M2 dashboard/exchange/quote flow.
M1's existing monitoring workspace is preserved at /rates with separate analyst login.

## Open it on this laptop

1. Start the updated Quote Service on port 8082. Its local source is in
   `FXLock-m2-publish/quote-service` in the sibling backend checkout on this laptop.
   The full handover ZIP contains a quote-service folder with the adapter included.
2. For Oracle: build using `mvn package`, then run `.\run-oracle.ps1` from quote-service.
   For a disposable demonstration: `.\run-demo.ps1`. Never combine these profiles.
3. In this frontend folder, run `powershell -NoProfile -ExecutionPolicy Bypass -File .\start-frontend.ps1`. It uses installed Node or
   the existing bundled Node runtime on this laptop. It installs dependencies if needed.
4. Open http://localhost:5173. Use customer-demo / learn-fxlock, unless DEMO_PASSWORD
   was changed in the backend. customer-other is the second training account.
5. Keep both services running. Refreshing a React route works through Vite's SPA fallback.

For teammates with Node installed:

```text
npm ci
npm run dev
```

Or use pnpm with pnpm-lock.yaml. The development proxy routes /m2 to the Quote Service
at 8082 and retains the original /api/v1 proxy to M1 on 8081. Set QUOTE_BACKEND_URL or
RATE_BACKEND_URL in the environment to change those targets. Hostname localhost must
be used consistently. Requests use session cookies and CSRF; no passwords or tokens
are saved in localStorage. The reverse proxy keeps login redirects on the frontend origin.

## Routes

| Route | Feature |
| --- | --- |
| / | Product landing page, with illustrative journey (no fake market quote) |
| /login | Existing Spring session login, validation and show/hide password |
| /dashboard | Owned balances by currency, recent ledger activity, rate availability |
| /exchange | EUR-to-USD input and eligible wallet selection |
| /exchange/quote/:quoteId | Stored quote, confidence, expiry, cancel and acceptance |
| /transactions | Owned ledger history, account/search/date/currency filters, pagination |
| /transactions/:id | Authenticated settlement receipt |
| /transfer/result/:id | Result retrieved from the backend, not optimistic success |
| /rates | Existing M1 monitoring workspace |

Registration, wallet creation and operational admin screens remain with their owners.
Their existing placeholder files are not presented as working features. /rates preserves
M1's own permission checks and credentials. No analyst permission is inferred from
customer login. Full role-based admin routing remains a team integration task.

## Backend adapter

The published M2 backend at commit 7208ab3 needs the additive Java changes in
`../backend-integration`. They are included in the working backend on this laptop.
They add authenticated read-only `/api/customer/overview` and
`/api/customer/settlements/{id}` routes and extend the existing history endpoint with
server-side search, currency, start and end filters. No database schema changes are added.
The existing transaction-history migration must already be installed on Oracle.

`start` is an inclusive UTC timestamp and `end` is exclusive. The date inputs label UTC
explicitly. Search matches owned customer names or settlement references across all
pages. History currently represents completed wallet entries. It does not invent pending
or failed entries; settlement receipts display the status actually returned by Oracle.

Decimal strings preserve Oracle NUMBER(19,4) precision. The browser never reprices a
stored quote. The countdown uses backend serverTime and a monotonic elapsed timer,
and acceptance still relies on backend expiry/funds checks. Idempotency keys and uncertain
acceptance state survive a tab reload. Unknown results offer history lookup and retry with
the same key, without automatically sending another transfer.

## Verification

```text
npm run build
npm run lint:m2
npm run test:unit
```

Java browser checks are supplied in backend-integration. They exercise real Vite-to-demo
backend requests, including failed login, reload, cancellation, full quote expiry, foreign
account access, mobile history and unavailable settlement. No financial fixtures are
committed to the user's Oracle database. Screenshots marked demo use disposable H2 data.
The generic `npm run lint` also checks M1 files and currently reports seven inherited
M1 lint errors. M1 source files were preserved; M2's own lint command passes.

The development proxy is not a production deployment configuration. For deployment,
serve the built dist assets through a same-origin reverse proxy and reproduce the /m2
and /api/v1 routing, cookie and redirect handling. Do not expose secrets through VITE_ vars.

This M2 frontend is published on the shared frontend branch. The main branch is unchanged.
The existing backend transaction-history commit remains on feature-quote-service; apply the
compatible adapter from backend-integration before using this frontend with that baseline.

## M2 page source files

- New quote: src/features/exchange/pages/NewQuotePage.jsx
- Quote review: src/features/exchange/pages/QuoteReviewPage.jsx
- Transaction history: src/features/transactions/pages/TransactionHistoryPage.jsx

DashboardPage.jsx, the login page, receipt pages and shared components support this flow.
ExchangePage.jsx keeps the original team scaffold import compatible with NewQuotePage.
