// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import "../../i18n";
import { Toast } from "./Toast";

beforeEach(() => vi.useFakeTimers());
afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

describe("Toast", () => {
  it("is announced politely and dismisses itself after its duration", () => {
    const onDismiss = vi.fn();
    render(<Toast title="Gespeichert" onDismiss={onDismiss} duration={3000} />);
    expect(screen.getByRole("status").getAttribute("aria-live")).toBe("polite");
    act(() => vi.advanceTimersByTime(2999));
    expect(onDismiss).not.toHaveBeenCalled();
    act(() => vi.advanceTimersByTime(1));
    expect(onDismiss).toHaveBeenCalledOnce();
  });

  it("undoes and dismisses once when undo is chosen", () => {
    const onUndo = vi.fn();
    const onDismiss = vi.fn();
    render(<Toast title="In den Papierkorb gelegt" onUndo={onUndo} onDismiss={onDismiss} />);
    fireEvent.click(screen.getByRole("button", { name: "Rückgängig" }));
    expect(onUndo).toHaveBeenCalledOnce();
    expect(onDismiss).toHaveBeenCalledOnce();
  });

  it("stays longer when it offers undo", () => {
    const onDismiss = vi.fn();
    render(<Toast title="In den Papierkorb gelegt" onUndo={() => undefined} onDismiss={onDismiss} />);
    act(() => vi.advanceTimersByTime(5000));
    expect(onDismiss).not.toHaveBeenCalled();
    act(() => vi.advanceTimersByTime(3000));
    expect(onDismiss).toHaveBeenCalledOnce();
  });
});
