import { useEffect, useState } from "react";
export default function useCountdown(view) {
  const [clock, setClock] = useState({view: null, elapsed: 0});
  useEffect(() => {
    if (!view) return;
    const started = performance.now();
    const timer = setInterval(() => setClock({view, elapsed: (performance.now() - started) / 1000}), 250);
    return () => clearInterval(timer);
  }, [view]);
  if (!view) return 0;
  const lifetime = (Date.parse(view.quote.expiresAt) - Date.parse(view.serverTime)) / 1000;
  return Math.max(0, Math.ceil(lifetime - (clock.view === view ? clock.elapsed : 0)));
}
