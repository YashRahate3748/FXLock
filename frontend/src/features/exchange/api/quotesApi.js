import { apiClient } from "../../../services/apiClient";
import { endpoints } from "../../../services/endpoints";
export const quotesApi = {
  options: () => apiClient.get(endpoints.wallets).then((r) => r.data),
  create: (body) => apiClient.post("/quotes", body).then((r) => r.data),
  get: (id) => apiClient.get(endpoints.quote(id)).then((r) => r.data),
  cancel: (id) =>
    apiClient.post(endpoints.quote(id) + "/cancel").then((r) => r.data),
  accept: (id, key) =>
    apiClient
      .post(endpoints.quote(id) + "/accept", null, {
        headers: { "Idempotency-Key": key },
        timeout: 20000,
      })
      .then((r) => r.data),
};
