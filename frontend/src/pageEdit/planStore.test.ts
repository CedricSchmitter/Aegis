import "fake-indexeddb/auto";
import { describe, expect, it } from "vitest";
import { addScanPages, emptyPlan } from "./plan";
import { clearPlan, loadPlan, savePlan } from "./planStore";

describe("page plan storage", () => {
  it("keeps each member's plan until it is cleared", async () => {
    const lisa = addScanPages(emptyPlan("lisa"), ["p1"]);
    const cedric = emptyPlan("cedric");
    await savePlan(lisa);
    await savePlan(cedric);

    expect(await loadPlan("lisa")).toEqual(lisa);
    await clearPlan("lisa");
    expect(await loadPlan("lisa")).toBeNull();
    // Another member's plan on the same phone is untouched.
    expect(await loadPlan("cedric")).toEqual(cedric);
  });
});
