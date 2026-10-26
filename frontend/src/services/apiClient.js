import axios from "axios";
import { parseBackendJson } from "./compatibility";
let csrf = null;
export const apiClient = axios.create({baseURL: "/m2", withCredentials: true, timeout: 15000,
  headers: {Accept: "application/json"}, transformResponse: [(text) => {
    try { return parseBackendJson(text); } catch { return text; }
  }]});
function expired() { window.dispatchEvent(new Event("fx-session-expired")); }
apiClient.interceptors.request.use((config) => {
  if (!["get", "head", "options"].includes((config.method || "get").toLowerCase())) {
    if (!csrf) throw new Error("Your security token is missing. Sign in again.");
    config.headers[csrf.header] = csrf.token;
  }
  return config;
});
apiClient.interceptors.response.use((response) => {
  if (!(response.headers["content-type"] || "").includes("application/json")) {
    if (new URL(response.request.responseURL).pathname === "/m2/login") { expired(); throw new Error("Your session has expired. Sign in again."); }
    throw new Error("The service returned an unexpected response.");
  }
  return response;
}, (error) => {
  if (error.response?.status === 401) expired();
  error.message = error.response?.data?.message || (error.code === "ECONNABORTED" ? "The request timed out. Check history before retrying a transfer." : error.response ? "The request could not be completed." : "Cannot reach FXLock. Check that the services are running.");
  return Promise.reject(error);
});
async function html(url, options = {}) {
  const response = await fetch(url, {credentials: "same-origin", cache: "no-store", ...options});
  if (!response.ok) throw new Error("Unable to reach the account service. Try again when FXLock is available.");
  return {response, doc: new DOMParser().parseFromString(await response.text(), "text/html")};
}
function formToken(doc) {
  const input = doc.querySelector('input[name="_csrf"]');
  if (!input?.value) throw new Error("Unable to open secure sign-in. Check Quote Service.");
  return input.value;
}
export async function loadSession() {
  const {response, doc} = await html("/m2/");
  if (new URL(response.url).pathname === "/m2/login") { csrf = null; throw new Error("Sign in to open your workspace."); }
  const token = doc.querySelector('meta[name="csrf-token"]')?.content;
  const header = doc.querySelector('meta[name="csrf-header"]')?.content;
  const form = doc.querySelector("#quote-form");
  const username = doc.querySelector("header > span > span")?.textContent.trim();
  if (!token || !header || !form || !username) throw new Error("The Quote Service page did not supply the expected session information.");
  csrf = {token, header};
  const amount = form.querySelector("#amount");
  const spreadText = [...doc.querySelectorAll("p.hint")].find((p) => p.textContent.includes("Configured spread"))?.querySelector("span")?.textContent.trim();
  const settings = {minAmount: amount.min, maxAmount: amount.max, spreadBps: spreadText || null};
  if (!settings.minAmount || !settings.maxAmount) throw new Error("Quote limits are unavailable. Refresh after Quote Service is ready.");
  const [{data: wallets}, {data: accounts}] = await Promise.all([apiClient.get("/api/v1/wallets"), apiClient.get("/api/transaction-history/accounts")]);
  let customerName = username;
  // Optional existing M3 dashboard session supplies the registered name. No new endpoint or identity assertion.
  try {
    const profile = await fetch("/m3/api/dashboard", {credentials: "same-origin", cache: "no-store"});
    if (profile.ok) { const account = (await profile.json()).account; if (account.customerRef === username) customerName = account.fullName || username; }
  } catch { /* M2 session remains usable if M3's dashboard session is absent. */ }
  return {username, customerName, settings,
    wallets: wallets.map((w) => ({walletId: String(w.walletId), currency: w.currencyCode, balance: String(w.balance), status: accounts.find((a) => String(a.walletId) === String(w.walletId))?.status || "UNKNOWN"})),
    rate: {available: false, message: "Request a quote to retrieve current trusted pricing. Market monitoring uses a separate analyst sign-in."}};
}
export async function signIn(username, password, demo = false) {
  const {doc} = await html(demo ? "/m2/login?demo=true" : "/m2/login");
  const action = demo ? "/m2/demo-login" : "/m2/login";
  const {response} = await html(action, {method: "POST", headers: {"Content-Type": "application/x-www-form-urlencoded"},
    body: new URLSearchParams({username, password, _csrf: formToken(doc)})});
  const result = new URL(response.url);
  if (result.pathname === "/m2/login") {
    if (result.searchParams.has("resetRequired")) throw new Error("This account requires a password reset before signing in.");
    if (result.searchParams.has("unavailable")) throw new Error("Account verification is unavailable. Check Settlement Service.");
    throw new Error(demo ? "The username or password is incorrect." : "The email or password is incorrect.");
  }
  return loadSession();
}
export async function signOut() {
  if (!csrf) await loadSession();
  // Invalidate the optional M3 session first; the M2 logout redirect also performs this handshake.
  const profile = await fetch("/m3/api/dashboard", {credentials: "same-origin", cache: "no-store"});
  if (profile.ok) {
    const data = await profile.json();
    const result = await fetch("/m3/api/dashboard/logout", {method: "POST", credentials: "same-origin", headers: {"X-CSRF-TOKEN": data.csrfToken}});
    if (!result.ok) throw new Error("Could not finish dashboard sign-out. Try again.");
  } else if (profile.status !== 401) throw new Error("Could not verify dashboard sign-out. Try again.");
  await html("/m2/logout", {method: "POST", headers: {[csrf.header]: csrf.token}});
  const check = await fetch("/m2/wallets/quote-options", {credentials: "same-origin", cache: "no-store"});
  if (check.status !== 401) throw new Error("Sign out could not be confirmed.");
  csrf = null;
  // Idempotency data remains scoped by customer so an uncertain transfer can still be recovered after login.
}
