# Backend adapter for the React M2 frontend

Base: FXLock feature-quote-service at commit 7208ab3. These Java changes add
read-only authenticated overview and receipt APIs, extend history with server-side
filters, and fix scientific-notation IDs in the disposable H2 demo gateway.
No database table, trigger, grant or migration is added or changed.

The complete updated quote-service is included in the handover source ZIP.
For an existing Git checkout at the compatible baseline, from the repository root:

```powershell
git apply --check <absolute-path-to-m2-customer-workspace.patch>
git apply <absolute-path-to-m2-customer-workspace.patch>
```

Stop if the check reports conflicts. Review and merge those changes with your
teammates instead of overwriting a newer controller. The quote-service tree here
also provides the exact changed/new source files for manual comparison.

Build with mvn package. Start using run-oracle.ps1 (saved Oracle credentials) or
run-demo.ps1 (disposable H2). Start Vite separately from frontend/start-frontend.ps1.
The original Thymeleaf pages continue to work on port 8082.

CustomerWorkspaceApiTest covers identity, exact balances/receipt values and filter
boundaries. ReactFrontendBrowserTest requires Vite 5173 and a disposable demo backend
8082, then mvn test -Dtest=ReactFrontendBrowserTest -DrunReact=true. It must not target
Oracle. The test creates/cancels demo quotes and does not commit real financial data.

The existing M2 history migration is still required when deploying to a new Oracle
schema. Do not rerun the common reset SQL. Production hosting needs a same-origin
reverse proxy reproducing Vite's development /m2 routing and login redirect handling.
