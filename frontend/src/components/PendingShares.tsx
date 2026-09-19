import { useEffect, useState } from "react";
import { Link, useLocation } from "react-router";
import { useTranslation } from "react-i18next";
import { listSharedFiles, openInbox } from "../share/inbox";

/** Points back to shared files that were neither saved nor discarded, e.g. after leaving the share page. */
export function PendingShares() {
  const { t } = useTranslation();
  const { pathname } = useLocation();
  const [count, setCount] = useState(0);

  useEffect(() => {
    if (pathname === "/share") {
      setCount(0);
      return;
    }
    let active = true;
    void (async () => {
      const inbox = await openInbox();
      if (!inbox) return;
      try {
        const files = await listSharedFiles(inbox);
        if (active) setCount(files.length);
      } catch {
        // an unreadable inbox is reported on the share page itself
      }
    })();
    return () => {
      active = false;
    };
  }, [pathname]);

  if (count === 0) return null;
  return (
    <p className="alert-info" role="status" data-testid="pending-shares">
      <Link to="/share" className="underline">
        {t("share.pending", { count })}
      </Link>
    </p>
  );
}
