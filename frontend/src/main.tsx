import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { BrowserRouter } from "react-router";
import "./i18n";
// Self-hosted fonts (ADR-0019); the app never requests Google Fonts. The browser downloads a subset only when the
// page contains characters from its unicode-range, so latin-ext is fetched only for names that need it.
import "@fontsource/chakra-petch/latin-400.css";
import "@fontsource/chakra-petch/latin-500.css";
import "@fontsource/chakra-petch/latin-600.css";
import "@fontsource/chakra-petch/latin-700.css";
import "@fontsource/chakra-petch/latin-ext-400.css";
import "@fontsource/chakra-petch/latin-ext-500.css";
import "@fontsource/chakra-petch/latin-ext-600.css";
import "@fontsource/chakra-petch/latin-ext-700.css";
import "@fontsource/share-tech-mono/latin-400.css";
import "./styles.css";
import { App } from "./App";
import { SessionProvider } from "./auth/SessionContext";

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <BrowserRouter>
      <SessionProvider>
        <App />
      </SessionProvider>
    </BrowserRouter>
  </StrictMode>,
);

// The service worker only receives Android shares (public/sw.js); it caches nothing, so the app works without it.
if (import.meta.env.PROD && "serviceWorker" in navigator) {
  window.addEventListener("load", () => {
    navigator.serviceWorker.register("/sw.js", { scope: "/" }).catch(() => undefined);
  });
}
