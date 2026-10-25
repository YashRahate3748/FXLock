import { useEffect, useState } from "react";
export default function useCountdown(view) {
  const [elapsed, setElapsed] = useState(0);
  useEffect(() => {
    if (!view) return;
    const started = performance.now();
    const timer = setInterval(
      () => setElapsed(Math.floor((performance.now() - started) / 1000)),
      250,
    );
    return () => clearInterval(timer);
  }, [view]);
  if (!view) return 0;
  return Math.max(
    0,
    Math.ceil(
      (Date.parse(view.quote.expiresAt) - Date.parse(view.serverTime)) / 1000,
    ) - elapsed,
  );
}
