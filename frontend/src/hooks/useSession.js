import { useContext } from "react";
import { SessionContext } from "../context/SessionContext";
export default function useSession() {
  return useContext(SessionContext);
}
