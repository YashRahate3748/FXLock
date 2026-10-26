# FXLock Customer Frontend

## Features
- Landing page, email login and registration
- Home dashboard and profile dropdown
- Wallet Overview with separate EUR/USD balances
- Deposit and withdrawal with password confirmation and recovery
- Five recent transactions per wallet
- Currency exchange, quote review, expiry and confidence
- Transaction history and settlement receipts
- Existing market rates and risk-monitoring workspace

## Run
Keep the existing FXLock backend services running on ports 8081–8083.

From the frontend directory:
    npm ci
    npm run dev

Open http://localhost:5174.

## Integration
Uses the existing backend APIs, sessions and CSRF protection.
No backend changes are included.
