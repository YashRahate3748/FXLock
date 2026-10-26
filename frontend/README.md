# FXLock compatible customer frontend

This checkout contains the customer designs from backup-frontend-before-cleanup,
adapted to the existing application at C:\fxlock-application\FXLock. Only this
frontend checkout is changed. Do not apply backend-integration patches or copy
Java files into the application.

## Run

Keep the existing Rate (8081), Quote (8082) and Settlement (8083) services running.
If starting the main project, use its existing launcher without rebuilding:

    cd /d C:\fxlock-application\FXLock
    node scripts/dev.mjs --no-build

In a second Command Prompt:

    cd /d C:\fxlock-application\frontend\frontend
    npm ci
    npm run dev

The browser opens http://localhost:5174/ automatically. Port 5174 leaves the
original application frontend on 5173 alone. Sign in with your existing registered
email and password. Demo username mode uses the existing backend's demo-login
route; its wallet eligibility still depends on the backend. PowerShell users can
use npm.cmd instead of npm. npm ci is only needed for dependency installation.

## Features and compatibility

Landing, login, customer dashboard, both EUR/USD directions, eligible destination
wallets, fixed quote review, server-based expiry, confidence, cancellation,
acceptance, safe retry, transaction history, settlement receipts and responsive
layouts use the backup branch designs. The unchanged M1 analyst workspace is at
/rates and retains its separate analyst credentials.

The frontend uses the existing M2 session, CSRF, wallet and quote endpoints. It
reads identity and configured amount limits/spread from M2's existing rendered
quote page. It reads balances through M2's authenticated /api/v1/wallets proxy,
history through /api/transaction-history and receipts through
/api/v1/settlements/{id}/details. No internal service token or database credential
is exposed to the browser. A registered login follows the existing M2/M3 session
handoff through development proxies. Customer names are used only when the M3
session identity matches the M2 session.

The backend does not expose a customer market overview endpoint. The dashboard
therefore invites a quote request for live pricing, rather than displaying an
invented rate or sharing analyst credentials. Every quote shows the actual stored
pricing and expiry from the server. The frontend does not calculate or reprice
financial offers. It preserves retry keys and uncertain outcomes across reloads,
including HTTP 503 responses.

The existing backend supports history filtering by wallet and server pagination.
Additional search/currency/date filters fetch all owned history pages first and
filter in the browser, then paginate. A safety limit of 10,000 entries applies;
exceeding it reports an error and asks for a wallet filter rather than showing
partial results. UTC start dates are inclusive and end dates include that whole
day. Deposits and withdrawals are shown as funding records, without broken FX
receipt links. Receipts display only the ledger rows returned by the backend.

Account creation is available at /register through the existing M2 registration endpoint. Other operational actions remain in the existing application.

## Configuration

Optional environment variables before npm run dev:
QUOTE_BACKEND_URL (default http://127.0.0.1:8082),
SETTLEMENT_BACKEND_URL (default http://127.0.0.1:8083),
RATE_BACKEND_URL (default http://127.0.0.1:8081).
Use localhost consistently in your browser. The proxy maps backend redirects to
/m2 and /m3 on the frontend origin and preserves separate HttpOnly sessions.
These development proxies must be reproduced by a same-origin reverse proxy for
a production deployment. The standalone dist folder alone cannot proxy APIs.

## Verification

    npm run build
    npm run lint:m2
    npm run test:unit

Optional browser checks: install Playwright separately or set PLAYWRIGHT_MODULE
to an existing Playwright package, with Chrome installed. Then run:

    node tests/customer.browser.mjs
    node tests/proxy.integration.mjs

These tests use disposable fixture responses, never write to the live services,
and cover login, reload, CSRF, both quote directions, expiry/cancel, uncertain
retry after reload, funding history/filtering, receipts, mobile layout and logout.
Proxy integration checks the full registered-login handoff and HttpOnly cookies
against disposable HTTP fixtures. Screenshots named compatible-dashboard-* use
fixture data. These checks do not establish live financial settlement results.
The inherited general M1 lint errors are outside the customer lint:m2 scope.

## Wallet deposits and withdrawals

The dashboard adds Deposit money and Withdraw money below the existing wallet cards.
Choose an active wallet, enter the amount and confirm with the current login password.
The dialog reuses M3's existing dashboard session, CSRF protection and funding endpoints.
Balances and recent activity refresh after a confirmed result. Uncertain requests retain
the original intent and key in session storage without the password; Recover funding
request checks the outcome or retries that same intent. No automatic financial retries
or backend changes are made. Tests/funding.browser.mjs uses only disposable fixtures.

## Registration

Choose Create account directly below Sign in on the login page. The registration
page uses the same mint/purple design and submits full name, email, password and
confirmation to the existing M2 /register form with its current CSRF token.
Backend validation errors are shown without storing passwords. A confirmed success
returns to login with the email filled in; it does not automatically log in.
Unconfirmed or failed requests are not retried automatically. Registration tests
use intercepted responses and do not create real database accounts.
