import { apiRequest } from "./apiClient";

export function getHealth() {
  return apiRequest(
    "/actuator/health",
    {
      authenticated: false,
    }
  );
}

export function getTrustedRate(
  username,
  password
) {
  return apiRequest(
    "/api/v1/rates/trusted",
    {
      username,
      password,
    }
  );
}

export function enableQuoting(
  username,
  password
) {
  return apiRequest(
    "/api/v1/risk/quoting/enable",
    {
      method: "POST",
      username,
      password,
    }
  );
}

export function disableQuoting(
  username,
  password
) {
  return apiRequest(
    "/api/v1/risk/quoting/disable",
    {
      method: "POST",
      username,
      password,
    }
  );
}

export function approveAnomaly(
  anomalyId,
  reason,
  username,
  password
) {
  return apiRequest(
    `/api/v1/risk/anomalies/${anomalyId}/approve`,
    {
      method: "POST",
      username,
      password,
      body: {
        reason,
      },
    }
  );
}

export function rejectAnomaly(
  anomalyId,
  reason,
  username,
  password
) {
  return apiRequest(
    `/api/v1/risk/anomalies/${anomalyId}/reject`,
    {
      method: "POST",
      username,
      password,
      body: {
        reason,
      },
    }
  );
}