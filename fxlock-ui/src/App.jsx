import { useEffect, useState } from "react";

import LoginPanel
  from "./components/LoginPanel";

import HealthCard
  from "./components/HealthCard";

import RateCard
  from "./components/RateCard";

import RiskControls
  from "./components/RiskControls";

import AnomalyReview
  from "./components/AnomalyReview";

import MessageBox
  from "./components/MessageBox";

import {
  getHealth,
  getTrustedRate,
  enableQuoting,
  disableQuoting,
  approveAnomaly,
  rejectAnomaly,
} from "./api/rateService";

function App() {

  const [username, setUsername] =
    useState("fxlock-service");

  const [password, setPassword] =
    useState("");

  const [health, setHealth] =
    useState(null);

  const [rate, setRate] =
    useState(null);

  const [healthLoading, setHealthLoading] =
    useState(false);

  const [rateLoading, setRateLoading] =
    useState(false);

  const [actionLoading, setActionLoading] =
    useState(false);

  const [message, setMessage] =
    useState("");

  const [messageType, setMessageType] =
    useState("info");

  useEffect(() => {
    loadHealth();
  }, []);

  async function loadHealth() {

    setHealthLoading(true);

    try {

      const result =
        await getHealth();

      setHealth(
        result?.status || "UNKNOWN"
      );

    } catch (error) {

      setHealth("DOWN");

      showMessage(
        error.message,
        "error"
      );

    } finally {

      setHealthLoading(false);
    }
  }

  async function loadRate() {

    if (!username || !password) {

      showMessage(
        "Enter backend username and password first.",
        "error"
      );

      return;
    }

    setRateLoading(true);

    try {

      const result =
        await getTrustedRate(
          username,
          password
        );

      setRate(result);

      showMessage(
        "Trusted rate loaded successfully.",
        "success"
      );

    } catch (error) {

      setRate(null);

      showMessage(
        error.message,
        "error"
      );

    } finally {

      setRateLoading(false);
    }
  }

  async function handleEnableQuoting() {

    await performAction(
      async () => {

        await enableQuoting(
          username,
          password
        );

        showMessage(
          "Quote generation enabled.",
          "success"
        );

        await loadRate();
      }
    );
  }

  async function handleDisableQuoting() {

    await performAction(
      async () => {

        await disableQuoting(
          username,
          password
        );

        setRate(null);

        showMessage(
          "Quote generation disabled.",
          "success"
        );
      }
    );
  }

  async function handleApprove(
    anomalyId,
    reason
  ) {

    await performAction(
      async () => {

        await approveAnomaly(
          anomalyId,
          reason,
          username,
          password
        );

        showMessage(
          `Anomaly ${anomalyId} approved.`,
          "success"
        );

        await loadRate();
      }
    );
  }

  async function handleReject(
    anomalyId,
    reason
  ) {

    await performAction(
      async () => {

        await rejectAnomaly(
          anomalyId,
          reason,
          username,
          password
        );

        showMessage(
          `Anomaly ${anomalyId} rejected.`,
          "success"
        );
      }
    );
  }

  async function performAction(action) {

    if (!username || !password) {

      showMessage(
        "Enter backend username and password first.",
        "error"
      );

      return;
    }

    setActionLoading(true);

    try {

      await action();

    } catch (error) {

      showMessage(
        error.message,
        "error"
      );

    } finally {

      setActionLoading(false);
    }
  }

  function showMessage(
    text,
    type
  ) {

    setMessage(text);
    setMessageType(type);
  }

  return (
    <div className="app">

      <header className="topbar">

        <div>
          <h1>FXLock</h1>

          <p>
            Market Rate & Risk Dashboard
          </p>
        </div>

        <span className="service-name">
          rate-service :8081
        </span>

      </header>

      <main className="container">

        <MessageBox
          message={message}
          type={messageType}
        />

        <div className="grid">

          <LoginPanel
            username={username}
            password={password}
            onUsernameChange={
              setUsername
            }
            onPasswordChange={
              setPassword
            }
          />

          <HealthCard
            health={health}
            loading={healthLoading}
            onRefresh={loadHealth}
          />

        </div>

        <RateCard
          rate={rate}
          loading={rateLoading}
          onRefresh={loadRate}
        />

        <div className="grid">

          <RiskControls
            loading={actionLoading}
            onEnable={
              handleEnableQuoting
            }
            onDisable={
              handleDisableQuoting
            }
          />

          <AnomalyReview
            loading={actionLoading}
            onApprove={
              handleApprove
            }
            onReject={
              handleReject
            }
          />

        </div>

      </main>

      <footer>
        FXLock Training Project
      </footer>

    </div>
  );
}

export default App;