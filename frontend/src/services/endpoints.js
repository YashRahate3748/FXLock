export const endpoints = {
  overview: "/api/customer/overview",
  wallets: "/wallets/quote-options",
  history: "/api/transaction-history",
  accounts: "/api/transaction-history/accounts",
  quote: (id) => "/quotes/" + encodeURIComponent(id),
  settlement: (id) => "/api/customer/settlements/" + encodeURIComponent(id),
};
