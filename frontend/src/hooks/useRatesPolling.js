import { useCallback, useEffect, useState } from "react";

export default function useRatesPolling(load, interval = 15000) {
  const [revision, setRevision] = useState(0);
  const [state, setState] = useState({
    data: null,
    error: null,
    loading: true,
  });

  const refresh = useCallback(() => {
    setRevision((value) => value + 1);
  }, []);

  useEffect(() => {
    let disposed = false;
    let timer;
    let controller;

    async function run() {
      clearTimeout(timer);
      controller?.abort();

      if (document.hidden) return;

      const current = new AbortController();
      controller = current;

      setState((previous) => ({
        ...previous,
        loading: true,
      }));

      try {
        const data = await load(current.signal);

        if (!disposed && !current.signal.aborted) {
          setState({ data, error: null, loading: false });
        }
      } catch (error) {
        if (!disposed && !current.signal.aborted) {
          setState((previous) => ({
            ...previous,
            error,
            loading: false,
          }));
        }
      } finally {
        if (!disposed && !current.signal.aborted) {
          timer = setTimeout(run, interval);
        }
      }
    }

    run();
    document.addEventListener("visibilitychange", run);

    return () => {
      disposed = true;
      clearTimeout(timer);
      controller?.abort();
      document.removeEventListener("visibilitychange", run);
    };
  }, [load, interval, revision]);

  return { ...state, refresh };
}