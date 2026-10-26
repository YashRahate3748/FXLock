// Kept in memory only. Never saved to localStorage or sessionStorage.
let authorization = null;

export function clearAnalystCredentials() {
  authorization = null;
}

function basicHeader(username, password) {
  const bytes = new TextEncoder().encode(`${username}:${password}`);
  const binary = Array.from(bytes, (byte) =>
    String.fromCharCode(byte),
  ).join("");

  return `Basic ${btoa(binary)}`;
}

async function request(path, { method = "GET", body, signal } = {}) {
  if (!authorization) {
    throw new Error("Sign in with your analyst credentials.");
  }

  const response = await fetch(`/api/v1${path}`, {
    method,
    signal,
    cache: "no-store",
    credentials: "omit",
    headers: {
      Accept: "application/json",
      Authorization: authorization,
      ...(body !== undefined
        ? { "Content-Type": "application/json" }
        : {}),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });

  if (!response.ok) {
    const payload = await response.json().catch(() => null);

    const fallback = {
      401: "Your credentials were rejected. Sign out and sign in again.",
      403: "Your account does not have permission for this operation.",
      404: "This record could not be found.",
      409: "The record changed or can no longer be reviewed. Refresh it.",
      503: "Rate-service is temporarily unavailable.",
    };

    const error = new Error(
      payload?.message ||
        fallback[response.status] ||
        `Request failed (${response.status}).`,
    );

    error.status = response.status;
    throw error;
  }

  if (response.status === 204) return null;
  return response.json();
}

export async function signInMonitoring(username, password) {
  const normalizedUsername = username.trim();

  if (!normalizedUsername || normalizedUsername.includes(":")) {
    throw new Error("Enter a valid username without a colon.");
  }

  authorization = basicHeader(normalizedUsername, password);

  try {
    // Both SERVICE and RISK_ANALYST can access this endpoint.
    await request("/rates/status");

    let canReviewRisk = false;

    try {
      // Only RISK_ANALYST can access this endpoint.
      await request("/risk/anomalies?page=0&size=1");
      canReviewRisk = true;
    } catch (error) {
      if (error.status !== 403) {
        throw error;
      }

      // Authenticated, but without risk permissions:
      // allow the read-only market workspace.
    }

    return {
      username: normalizedUsername,
      canReviewRisk,
    };
  } catch (error) {
    clearAnalystCredentials();
    throw error;
  }
}

export const ratesApi = {
  status(signal) {
    return request("/rates/status", { signal });
  },

  history(hours, signal) {
    const to = new Date();
    const from = new Date(to.getTime() - hours * 60 * 60 * 1000);

    const query = new URLSearchParams({
      from: from.toISOString(),
      to: to.toISOString(),
    });

    return request(`/rates/history?${query}`, { signal });
  },

  anomalies(status, page, signal) {
    const query = new URLSearchParams({
      page: String(page),
      size: "20",
    });

    if (status) query.set("status", status);

    return request(`/risk/anomalies?${query}`, { signal });
  },

  anomaly(id, signal) {
    return request(`/risk/anomalies/${id}`, { signal });
  },

  review(id, action, reason) {
    if (!["approve", "reject"].includes(action)) {
      throw new Error("Unsupported review action.");
    }

    return request(`/risk/anomalies/${id}/${action}`, {
      method: "POST",
      body: { reason },
    });
  },

  setQuoting(enabled) {
    return request(`/risk/quoting/${enabled ? "enable" : "disable"}`, {
      method: "POST",
    });
  },
};