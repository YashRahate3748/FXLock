import axios from "axios";
let csrf = null;
export const apiClient = axios.create({
  baseURL: "/m2",
  withCredentials: true,
  timeout: 15000,
  headers: { Accept: "application/json" },
});
apiClient.interceptors.request.use((config) => {
  if (
    !["get", "head", "options"].includes((config.method || "get").toLowerCase())
  ) {
    if (!csrf)
      throw new Error("Your security token is missing. Sign in again.");
    config.headers[csrf.header] = csrf.token;
  }
  return config;
});
apiClient.interceptors.response.use(
  (response) => {
    if (!(response.headers["content-type"] || "").includes("application/json"))
      throw new Error(
        "The service returned an unexpected response. Check your connection and sign in again.",
      );
    return response;
  },
  (error) => {
    if (error.response?.status === 401)
      window.dispatchEvent(new Event("fx-session-expired"));
    error.message =
      error.response?.data?.message ||
      (error.code === "ECONNABORTED"
        ? "The request timed out. Check the result before retrying."
        : error.response
          ? "The request could not be completed."
          : "Cannot reach FXLock. Check that Quote Service is running.");
    return Promise.reject(error);
  },
);
async function securityToken() {
  const response = await fetch("/m2/", {
    credentials: "same-origin",
    cache: "no-store",
  });
  const doc = new DOMParser().parseFromString(
    await response.text(),
    "text/html",
  );
  const token = doc.querySelector('meta[name="csrf-token"]')?.content,
    header = doc.querySelector('meta[name="csrf-header"]')?.content;
  if (!token || !header)
    throw new Error("Your session has expired. Please sign in again.");
  csrf = { token, header };
}
export async function loadSession() {
  const { data } = await apiClient.get("/api/customer/overview");
  await securityToken();
  return data;
}
export async function signIn(username, password) {
  const login = await fetch("/m2/login", {
    credentials: "same-origin",
    cache: "no-store",
  });
  const doc = new DOMParser().parseFromString(await login.text(), "text/html"),
    token = doc.querySelector('input[name="_csrf"]')?.value;
  if (!token)
    throw new Error("Unable to open secure sign-in. Check Quote Service.");
  const response = await fetch("/m2/login", {
    method: "POST",
    credentials: "same-origin",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ username, password, _csrf: token }),
  });
  if (!response.ok || new URL(response.url).searchParams.has("error"))
    throw new Error("The username or password is incorrect.");
  return loadSession();
}
export async function signOut() {
  if (!csrf) throw new Error("Refresh the page before signing out.");
  await fetch("/m2/logout", {
    method: "POST",
    credentials: "same-origin",
    headers: { [csrf.header]: csrf.token },
  });
  // Spring can reject the redirected login document for an API-style request.
  // Confirm session invalidation through the protected API rather than that document.
  const check = await fetch("/m2/api/customer/overview", {
    credentials: "same-origin", cache: "no-store", headers: {Accept: "application/json"},
  });
  if (check.status !== 401) throw new Error("Sign out could not be confirmed. Please try again.");
  csrf = null;
}
