import { useCallback, useEffect, useState } from "react";
import { useLocation } from "react-router";
import { useTranslation } from "react-i18next";
import { api } from "../api/client";
import { BigTitle } from "../components/common";
import { ContextPanelOrFlow, usePanelShown } from "../components/shell/ContextPanel";
import { useDesktop, useShellData } from "../components/shell/ShellData";
import { SegmentedControl } from "../components/ui";
import { INBOX_CHANGED, MESSAGES_ANCHOR } from "../lib/inbox";
import { MessagesSection } from "./inbox/MessagesSection";
import { ReviewSection } from "./inbox/ReviewSection";

type Section = "review" | "messages";

function SegmentCount({ count }: { count: number }) {
  return count > 0 ? <span className="font-mono text-xs text-action">{count}</span> : null;
}

/**
 * The combined inbox (issues #83 and #27): the shared review cards to decide and the member's messages. A phone shows
 * one of them at a time as two segments; a desktop shows them side by side, from 1280 px with the messages in the
 * context panel. Notices announcing a review card are not listed; once the cards are shown, they are marked read.
 */
export function InboxPage() {
  const { t } = useTranslation();
  const { hash } = useLocation();
  const desktop = useDesktop();
  const panelShown = usePanelShown();
  const { openCards, unreadMessages } = useShellData();
  // Old "/notices" links and a new message with nothing to review open the messages; otherwise the cards come first.
  const [section, setSection] = useState<Section>(() =>
    hash === `#${MESSAGES_ANCHOR}` || (openCards === 0 && unreadMessages > 0) ? "messages" : "review",
  );
  useEffect(() => {
    if (hash === `#${MESSAGES_ANCHOR}`) setSection("messages");
  }, [hash]);

  // Runs after every load of the cards, because loading reconciles: that may open or close cards and publish card
  // notices, so the badge is refreshed. The card notices are read only when the open cards are what the member sees.
  const cardsLoaded = useCallback((openShown: boolean) => {
    const changed = () => window.dispatchEvent(new Event(INBOX_CHANGED));
    if (openShown) void api.POST("/api/notices/review-cards/read").finally(changed);
    else changed();
  }, []);

  // Both sections stay mounted on a phone, so switching keeps their state and loading them works as before.
  return (
    <>
      <BigTitle count={t("inbox.toReview", { count: openCards })}>{t("inbox.title")}</BigTitle>
      {!desktop && (
        <div className="mb-4">
          <SegmentedControl<Section>
            label={t("inbox.sections")}
            value={section}
            onChange={setSection}
            options={[
              {
                value: "review",
                label: (
                  <>
                    {t("review.title")}
                    <SegmentCount count={openCards} />
                  </>
                ),
              },
              {
                value: "messages",
                label: (
                  <>
                    {t("notices.title")}
                    <SegmentCount count={unreadMessages} />
                  </>
                ),
              },
            ]}
          />
        </div>
      )}
      <div className={desktop && !panelShown ? "grid grid-cols-2 items-start gap-6" : "flex flex-col gap-6"}>
        <ReviewSection onLoaded={cardsLoaded} hidden={!desktop && section !== "review"} compactHeading={!desktop} />
        <ContextPanelOrFlow>
          <MessagesSection hidden={!desktop && section !== "messages"} compactHeading={!desktop} />
        </ContextPanelOrFlow>
      </div>
    </>
  );
}
