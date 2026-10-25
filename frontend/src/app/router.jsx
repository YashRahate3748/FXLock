import { Navigate, Outlet, useLocation, Routes, Route } from "react-router-dom";
import useSession from "../hooks/useSession";
import LoadingSpinner from "../components/feedback/LoadingSpinner";
import MainLayout from "../components/layout/MainLayout";
import LoginPage from "../features/auth/pages/LoginPage";
import LandingPage from "../features/dashboard/pages/LandingPage";
import DashboardPage from "../features/dashboard/pages/DashboardPage";
import NewQuotePage from "../features/exchange/pages/NewQuotePage";
import QuoteReviewPage from "../features/exchange/pages/QuoteReviewPage";
import TransactionHistoryPage from "../features/transactions/pages/TransactionHistoryPage";
import TransactionDetailsPage from "../features/transactions/pages/TransactionDetailsPage";
import TransferResultPage from "../features/transactions/pages/TransferResultPage";
import RatesWorkspace from "../features/rates/RatesWorkspace";
function Protected() {
  const { session, loading } = useSession(),
    location = useLocation();
  if (loading) return <LoadingSpinner />;
  return session ? (
    <Outlet />
  ) : (
    <Navigate
      to="/login"
      state={{ from: location.pathname + location.search }}
      replace
    />
  );
}
export default function Router() {
  return (
    <Routes>
      <Route path="/" element={<LandingPage />} />
      <Route path="/login" element={<LoginPage />} />
      <Route path="/rates/*" element={<RatesWorkspace />} />
      <Route element={<Protected />}>
        <Route element={<MainLayout />}>
          <Route path="/dashboard" element={<DashboardPage />} />
          <Route path="/exchange" element={<NewQuotePage />} />
          <Route
            path="/exchange/quote/:quoteId"
            element={<QuoteReviewPage />}
          />
          <Route path="/transactions" element={<TransactionHistoryPage />} />
          <Route
            path="/transactions/:id"
            element={<TransactionDetailsPage />}
          />
          <Route path="/transfer/result/:id" element={<TransferResultPage />} />
        </Route>
      </Route>
      <Route path="*" element={<Navigate to="/dashboard" replace />} />
    </Routes>
  );
}
