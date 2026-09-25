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

  // Runs after the cards were loaded, because loading them reconciles: that may publish new card notices and open new
  // cards, so the badge is refreshed as well.
  const cardsShown = useCallback(() => {
    void api.POST("/api/notices/review-cards/read").finally(() => window.dispatchEvent(new Event(INBOX_CHANGED)));
  }, []);

  return (
    <>
      <PageTitle>{t("inbox.title")}</PageTitle>
      <ReviewSection onLoaded={cardsShown} />
      <MessagesSection />
    </>
  );
}
