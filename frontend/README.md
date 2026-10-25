# FXLock React frontend

Requires a current Node.js LTS release and npm.

From PowerShell:

```powershell
cd C:\fxlock-application\FXLock\frontend
npm.cmd install
npm.cmd run dev
```

Open http://localhost:5173. Edit src/App.jsx to build the interface.
Tailwind CSS v4 is enabled through the Vite plugin and src/index.css.

Build for deployment with npm.cmd run build. Output goes to dist/.
The first successful npm install creates package-lock.json; commit it to pin dependencies.

This standalone starter does not replace the existing Spring web pages or change
Java services, authentication, CSRF, database operations, or application launchers.
Connecting it to FXLock requires an explicit integration of browser sessions,
CSRF handling, and backend routes before using financial actions.

Installation was blocked by npm error UNABLE_TO_VERIFY_LEAF_SIGNATURE on this
machine. Resolve the trusted certificate chain for registry.npmjs.org and retry
npm.cmd install. Do not disable TLS certificate verification.
