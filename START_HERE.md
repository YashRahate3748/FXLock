# FXLock M2 React frontend

Open http://localhost:5173 after starting the frontend and compatible Quote Service. Sign in with customer-demo / learn-fxlock. No separate history login.

The React source is in frontend/. The updated Spring backend source is in the
quote-service/ folder of the handover ZIP; on this laptop it is in the sibling
FXLock-m2-publish/quote-service checkout. The existing STS project
quote-service-history was updated with the compatible Java source files.
Refresh that project in STS before building. Stop another app using port 8082 first.

## Start again

1. In the updated quote-service folder: mvn package, then .\run-oracle.ps1.
2. In frontend/: powershell -NoProfile -ExecutionPolicy Bypass -File .\start-frontend.ps1.
3. Open http://localhost:5173/login.

For a disposable UI demonstration, use run-demo.ps1 instead of run-oracle.ps1.
Demo quotes use synthetic rates and deliberately cannot settle through M3.
Oracle mode needs fresh M1 data for new quotes and a configured, running M3 for
settlement. Existing quotes and transaction history remain available without a
fresh rate. The frontend never invents a successful transfer or live rate.

Screenshots in frontend/verification/dashboard-oracle.png and history-oracle.png
show the real local Oracle-backed UI. The mobile demo screenshot uses disposable
in-memory data. Test details are in frontend/verification/test-results.txt.

Read frontend/README.md for routes, security, testing and production deployment.
Read backend-integration/README.md before applying its Java patch to another checkout.
No original schema SQL was changed, no new migration is required for this frontend,
and the frontend is published to the frontend branch. Nothing was merged into main.
