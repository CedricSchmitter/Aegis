import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { BrowserRouter } from "react-router";
import "./i18n";
// Self-hosted fonts (ADR-0019); the app never requests Google Fonts. The per-weight files declare each subset with
// its unicode-range, so a browser downloads only the subsets a page needs (latin for German and English text).
import "@fontsource/chakra-petch/400.css";
import "@fontsource/chakra-petch/500.css";
import "@fontsource/chakra-petch/600.css";
import "@fontsource/chakra-petch/700.css";
import "@fontsource/share-tech-mono/400.css";
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
