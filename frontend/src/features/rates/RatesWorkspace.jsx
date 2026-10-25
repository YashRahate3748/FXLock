import React, { useState } from "react";

import {
  clearAnalystCredentials,
  signInMonitoring,
} from "./api/ratesApi";

import MarketRatesPage from "./pages/MarketRatesPage";
import RiskMonitorPage from "./pages/RiskMonitorPage";

import "./rates.css";

export default function RatesWorkspace() {
  const [username, setUsername] = useState("");
  const [signedIn, setSignedIn] = useState(false);
  const [canReviewRisk, setCanReviewRisk] = useState(false);

  const [page, setPage] = useState("rates");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  // Both navigation and rendering respect the returned permissions.
  const showingRisk = page === "risk" && canReviewRisk;

  async function signIn(event) {
    event.preventDefault();

    setBusy(true);
    setError("");

    const form = event.currentTarget;
    const values = new FormData(form);

    try {
      const access = await signInMonitoring(
        String(values.get("username") ?? ""),
        String(values.get("password") ?? ""),
      );

      form.reset();

      setUsername(access.username);
      setCanReviewRisk(access.canReviewRisk === true);
      setPage("rates");
      setSignedIn(true);
    } catch (failure) {
      setError(failure.message || "Unable to sign in.");
    } finally {
      setBusy(false);
    }
  }

  function signOut() {
    clearAnalystCredentials();

    setSignedIn(false);
    setUsername("");
    setCanReviewRisk(false);
    setPage("rates");
    setError("");
  }

  if (!signedIn) {
    return (
      <div className="fx-root fx-login">
        <section className="fx-login-intro">
          <div className="fx-brand">
            FX<span>Lock</span> ↗
          </div>

          <p className="fx-eyebrow">
            MARKET OPERATIONS
          </p>

          <h1>
            Clarity in every currency move.
          </h1>

          <p>
            A dedicated workspace for trusted rates and informed
            risk decisions.
          </p>
        </section>

        <section className="fx-login-card">
          <p className="fx-eyebrow">
            MARKET OPERATIONS ACCESS
          </p>

          <h2>Welcome back.</h2>

          <p className="fx-muted">
            Sign in with your rate-service account.
            Risk controls require analyst permissions.
          </p>

          {error && (
            <div
              className="fx-notice error"
              role="alert"
            >
              {error}
            </div>
          )}

          <form onSubmit={signIn}>
            <label htmlFor="fx-username">
              Username

              <input
                id="fx-username"
                name="username"
                type="text"
                autoComplete="username"
                autoCapitalize="none"
                spellCheck={false}
                disabled={busy}
                required
              />
            </label>

            <label htmlFor="fx-password">
              Password

              <input
                id="fx-password"
                name="password"
                type="password"
                autoComplete="current-password"
                disabled={busy}
                required
              />
            </label>

            <button
              type="submit"
              className="fx-primary"
              disabled={busy}
            >
              {busy ? "Signing in…" : "Sign in →"}
            </button>
          </form>

          <p className="fx-muted">
            Service accounts can view market rates.
            Risk analysts can also review anomalies and control quoting.
          </p>
        </section>
      </div>
    );
  }

  return (
    <div className="fx-root fx-app">
      <aside className="fx-sidebar">
        <div className="fx-brand">
          FX<span>Lock</span> ↗
        </div>

        <p className="fx-eyebrow">
          OPERATIONS
        </p>

        <nav aria-label="Market operations">
          <button
            type="button"
            className={!showingRisk ? "active" : ""}
            aria-current={!showingRisk ? "page" : undefined}
            onClick={() => setPage("rates")}
          >
            ↗ Market rates
          </button>

          {canReviewRisk && (
            <button
              type="button"
              className={showingRisk ? "active" : ""}
              aria-current={showingRisk ? "page" : undefined}
              onClick={() => setPage("risk")}
            >
              ◇ Risk monitor
            </button>
          )}
        </nav>

        <div className="fx-account">
          <strong>{username}</strong>

          <small>
            {canReviewRisk ? "Risk analyst" : "Market viewer"}
          </small>

          <button
            type="button"
            onClick={signOut}
          >
            Sign out
          </button>
        </div>
      </aside>

      <main className="fx-main">
        <div className="fx-topbar">
          FXLock / {showingRisk ? "Risk monitor" : "Market rates"}
        </div>

        {showingRisk ? (
          <RiskMonitorPage />
        ) : (
          <MarketRatesPage />
        )}
      </main>
    </div>
  );
}