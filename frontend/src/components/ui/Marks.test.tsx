// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import "../../i18n";
import { Chip } from "./Marks";
import { Countdown, countdownTone } from "./Countdown";
import { ProcessingSteps, stepStates } from "./ProcessingSteps";

afterEach(cleanup);

describe("Chip", () => {
  it("names its remove button after the chip", () => {
    const onRemove = vi.fn();
    render(
      <Chip facet="Org" onRemove={onRemove}>
        Telekom
      </Chip>,
    );
    fireEvent.click(screen.getByRole("button", { name: "Telekom entfernen" }));
    expect(onRemove).toHaveBeenCalledOnce();
  });

  it("has no button when it cannot be removed", () => {
    render(<Chip>Telekom</Chip>);
    expect(screen.queryByRole("button")).toBeNull();
  });
});

describe("stepStates", () => {
  it("marks finished steps done and the next one by its current state", () => {
    expect(stepStates(0, "running")).toEqual(["running", "pending", "pending", "pending"]);
    expect(stepStates(2, "failed")).toEqual(["done", "done", "failed", "pending"]);
    expect(stepStates(1, "waiting")).toEqual(["done", "pending", "pending", "pending"]);
    expect(stepStates(4, "running")).toEqual(["done", "done", "done", "done"]);
  });
});

describe("ProcessingSteps", () => {
  it("marks the running step as current and states each step in text", () => {
    render(<ProcessingSteps completed={1} />);
    const current = screen.getByText("Archiviert").closest("li");
    expect(current?.getAttribute("aria-current")).toBe("step");
    expect(current?.textContent).toContain("läuft");
    expect(screen.getByText("Gespeichert").closest("li")?.textContent).toContain("erledigt");
  });
});

describe("Countdown", () => {
  it("turns red when overdue and asks for action in the last week", () => {
    expect(countdownTone(-1)).toBe("danger");
    expect(countdownTone(0)).toBe("attention");
    expect(countdownTone(7)).toBe("attention");
    expect(countdownTone(8)).toBe("neutral");
  });

  it("reads the number with its unit and counts overdue days without a minus sign", () => {
    render(<Countdown days={-3} />);
    expect(screen.getByText("3 Tage überfällig")).toBeTruthy();
    cleanup();
    render(<Countdown days={1} />);
    expect(screen.getByText("1 Tag übrig")).toBeTruthy();
  });
});
