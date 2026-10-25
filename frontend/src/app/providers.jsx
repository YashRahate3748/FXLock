import { useEffect, useState, useCallback, useRef } from "react";
import { SessionContext } from "../context/SessionContext";
import { loadSession, signIn, signOut } from "../services/apiClient";
export default function Providers({ children }) {
  const generation = useRef(0);
  const [session, setSession] = useState(null),
    [loading, setLoading] = useState(true);
  useEffect(() => {
    let active = true;
    const current = generation.current;
    loadSession()
      .then((data) => {
        if (active && current === generation.current) setSession(data);
      })
      .catch(() => {})
      .finally(() => {
        if (active) setLoading(false);
      });
    const expired = () => setSession(null);
    window.addEventListener("fx-session-expired", expired);
    return () => {
      active = false;
      window.removeEventListener("fx-session-expired", expired);
    };
  }, []);
  const login = async (username, password) => {
    generation.current++;
    const data = await signIn(username, password);
    setSession(data);
    return data;
  };
  const logout = async () => {
    generation.current++;
    await signOut();
    setSession(null);
  };
  const refresh = useCallback(async () => {
    const current = generation.current;
    const data = await loadSession();
    if (current === generation.current) setSession(data);
    return data;
  }, []);
  return (
    <SessionContext.Provider
      value={{ session, loading, login, logout, refresh }}
    >
      {children}
    </SessionContext.Provider>
  );
}
