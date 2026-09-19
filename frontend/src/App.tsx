import { Navigate, Route, Routes, useLocation } from "react-router";
import { useTranslation } from "react-i18next";
import { useSession } from "./auth/SessionContext";
import { Layout } from "./components/Layout";
import { LoginPage } from "./pages/LoginPage";
import { TodayPage } from "./pages/TodayPage";
import { DocumentsPage } from "./pages/DocumentsPage";
import { DocumentDetailPage } from "./pages/DocumentDetailPage";
import { FoldersPage } from "./pages/FoldersPage";
import { LabelsPage } from "./pages/LabelsPage";
import { TasksPage } from "./pages/TasksPage";
import { TaskDetailPage } from "./pages/TaskDetailPage";
import { NoticesPage } from "./pages/NoticesPage";
import { StatusPage } from "./pages/StatusPage";
import { SettingsPage } from "./pages/SettingsPage";
import { SharePage } from "./pages/SharePage";

export function App() {
  const { member, loading } = useSession();
  const { t } = useTranslation();
  const location = useLocation();

  if (loading) {
    return (
      <main className="flex min-h-screen items-center justify-center" aria-busy="true">
        <p className="text-stone-500">{t("app.loading")}</p>
      </main>
    );
  }
  if (!member) {
    return (
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route path="*" element={<Navigate to="/login" replace state={{ from: location.pathname + location.search }} />} />
      </Routes>
    );
  }
  return (
    <Layout>
      <Routes>
        <Route path="/" element={<Navigate to="/today" replace />} />
        <Route path="/login" element={<Navigate to="/today" replace />} />
        <Route path="/today" element={<TodayPage />} />
        <Route path="/documents" element={<DocumentsPage />} />
        <Route path="/documents/folders" element={<FoldersPage />} />
        <Route path="/documents/labels" element={<LabelsPage />} />
        <Route path="/documents/:id" element={<DocumentDetailPage />} />
        <Route path="/tasks" element={<TasksPage />} />
        <Route path="/tasks/:id" element={<TaskDetailPage />} />
        <Route path="/notices" element={<NoticesPage />} />
        <Route path="/status" element={<StatusPage />} />
        <Route path="/settings" element={<SettingsPage />} />
        <Route path="/share" element={<SharePage />} />
        {/* Shares arrive as POST and are answered by the service worker; a plain visit lands on the share page. */}
        <Route path="/share-target" element={<Navigate to="/share" replace />} />
        <Route path="*" element={<Navigate to="/today" replace />} />
      </Routes>
    </Layout>
  );
}
