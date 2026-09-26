import { useCallback } from "react";
import { useTranslation } from "react-i18next";
import { api } from "../api/client";
import { PageTitle } from "../components/common";
import { INBOX_CHANGED } from "../lib/inbox";
import { MessagesSection } from "./inbox/MessagesSection";
import { ReviewSection } from "./inbox/ReviewSection";

/**
 * The combined inbox (issue #83): the shared review cards to decide, then the member's messages. Notices announcing a
 * review card are not listed; once the cards are shown, they are marked read for this member.
 */
export function InboxPage() {
  const { t } = useTranslation();

  // Runs after every load of the cards, because loading reconciles: that may open or close cards and publish card
  // notices, so the badge is refreshed. The card notices are read only when the open cards are what the member sees.
  const cardsLoaded = useCallback((openShown: boolean) => {
    const changed = () => window.dispatchEvent(new Event(INBOX_CHANGED));
    if (openShown) void api.POST("/api/notices/review-cards/read").finally(changed);
    else changed();
  }, []);

  return (
    <>
      <PageTitle>{t("inbox.title")}</PageTitle>
      <ReviewSection onLoaded={cardsLoaded} />
      <MessagesSection />
    </>
  );
}
