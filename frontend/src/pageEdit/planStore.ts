import { indexedDb } from "../lib/indexedDb";
import type { PagePlan } from "./plan";

/**
 * Keeps each member's page editor plan in the browser's IndexedDB (ADR-0024), so that it survives Android closing
 * the tab while the camera app is open. The new pages' photos live in the page editor's scan store; the plan only
 * refers to them. Best effort, like the scan store: without IndexedDB the plan lives in memory only.
 */
const PLANS = "plans";

const run = indexedDb("aegis-page-edit", 1, (db) => {
  db.createObjectStore(PLANS, { keyPath: "memberId" });
});

export async function loadPlan(memberId: string): Promise<PagePlan | null> {
  const plan = await run<PagePlan>([PLANS], "readonly", (tx) => tx.objectStore(PLANS).get(memberId));
  return plan && Array.isArray(plan.outputs) ? plan : null;
}

export async function savePlan(plan: PagePlan): Promise<void> {
  await run([PLANS], "readwrite", (tx) => {
    tx.objectStore(PLANS).put(plan);
  });
}

export async function clearPlan(memberId: string): Promise<void> {
  await run([PLANS], "readwrite", (tx) => {
    tx.objectStore(PLANS).delete(memberId);
  });
}
