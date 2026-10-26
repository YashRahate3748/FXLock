import { BrowserRouter } from "react-router-dom";
import Providers from "./providers";
import Router from "./router";
export default function App() {
  return (
    <BrowserRouter>
      <Providers>
        <Router />
      </Providers>
    </BrowserRouter>
  );
}
