export const endpoints = {
  wallets: "/wallets/quote-options", history: "/api/transaction-history", accounts: "/api/transaction-history/accounts",
  quote: (id) => "/quotes/" + encodeURIComponent(id),
  settlement: (id) => "/api/v1/settlements/" + encodeURIComponent(id) + "/details",
};
