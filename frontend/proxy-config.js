// Development routing only. All authentication and financial decisions stay in Spring.
export function rewriteLocation(location, prefix, target, targets) {
  if (!location) return location;
  const url = new URL(location, target);
  for (const [route, upstream] of Object.entries(targets)) {
    const expected = new URL(upstream);
    const loopback = new Set(["localhost", "127.0.0.1", "[::1]"]);
    const sameHost = url.hostname === expected.hostname || (loopback.has(url.hostname) && loopback.has(expected.hostname));
    if (url.protocol === expected.protocol && url.port === expected.port && sameHost)
      return route + url.pathname + url.search + url.hash;
  }
  if (location.startsWith("/") && !location.startsWith("//")) return prefix + location;
  return location;
}
export function backendProxy(env = {}) {
  const targets = {
    "/m2": env.QUOTE_BACKEND_URL || "http://127.0.0.1:8082",
    "/m3": env.SETTLEMENT_BACKEND_URL || "http://127.0.0.1:8083",
  };
  const proxy = { "/api/v1": { target: env.RATE_BACKEND_URL || "http://127.0.0.1:8081", changeOrigin: true } };
  for (const [prefix, target] of Object.entries(targets)) {
    proxy[prefix] = { target, changeOrigin: true,
      rewrite: (url) => url.slice(prefix.length) || "/",
      configure(server) {
        server.on("proxyRes", (response) => {
          if (response.headers.location)
            response.headers.location = rewriteLocation(response.headers.location, prefix, target, targets);
          // Sessions remain HttpOnly. Scope cookies to this browser origin in development.
          if (response.headers["set-cookie"])
            response.headers["set-cookie"] = response.headers["set-cookie"].map((cookie) => cookie.replace(/;\s*Domain=[^;]+/ig, "").replace(/;\s*Path=[^;]+/ig, "; Path=/"));
        });
      },
    };
  }
  return proxy;
}
