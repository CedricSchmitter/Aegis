// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import "../../i18n";
import { ConfirmDialog, Dialog, matchesConfirmation } from "./Dialog";

afterEach(cleanup);

describe("matchesConfirmation", () => {
  it("ignores case and surrounding spaces but not the word", () => {
    expect(matchesConfirmation("  löschen ", "LÖSCHEN")).toBe(true);
    expect(matchesConfirmation("LOESCHEN", "LÖSCHEN")).toBe(false);
    expect(matchesConfirmation("", "LÖSCHEN")).toBe(false);
  });
});

describe("Dialog", () => {
  it("is named by its title and reports Escape as close", () => {
    const onClose = vi.fn();
    render(
      <Dialog open onClose={onClose} title="Ordner löschen">
        <p>Text</p>
      </Dialog>,
    );
    const dialog = screen.getByRole("dialog", { name: "Ordner löschen" });
    const cancel = new Event("cancel", { cancelable: true });
    dialog.dispatchEvent(cancel);
    expect(cancel.defaultPrevented).toBe(true);
    expect(onClose).toHaveBeenCalledOnce();
  });
});

describe("ConfirmDialog", () => {
  it("keeps the destructive button disabled until the word is typed", () => {
    const onConfirm = vi.fn();
    render(
      <ConfirmDialog open onClose={() => undefined} onConfirm={onConfirm} title="Papierkorb leeren?" confirmWord="LÖSCHEN" confirmLabel="Endgültig löschen">
        Alle Einträge werden unwiderruflich gelöscht.
      </ConfirmDialog>,
    );
    const confirm = screen.getByRole("button", { name: "Endgültig löschen" }) as HTMLButtonElement;
    expect(confirm.disabled).toBe(true);
    const input = screen.getByRole("textbox", { name: "Zur Bestätigung „LÖSCHEN“ eingeben" });
    fireEvent.change(input, { target: { value: "lösch" } });
    fireEvent.click(confirm);
    expect(onConfirm).not.toHaveBeenCalled();
    fireEvent.change(input, { target: { value: "löschen" } });
    expect(confirm.disabled).toBe(false);
    fireEvent.click(confirm);
    expect(onConfirm).toHaveBeenCalledOnce();
  });

  it("clears the typed word when it is cancelled", () => {
    const onClose = vi.fn();
    render(
      <ConfirmDialog open onClose={onClose} onConfirm={() => undefined} title="Papierkorb leeren?" confirmWord="LÖSCHEN" confirmLabel="Endgültig löschen">
        Text
      </ConfirmDialog>,
    );
    const input = screen.getByRole("textbox") as HTMLInputElement;
    fireEvent.change(input, { target: { value: "LÖSCHEN" } });
    fireEvent.click(screen.getByRole("button", { name: "Abbrechen" }));
    expect(onClose).toHaveBeenCalledOnce();
    expect(input.value).toBe("");
  });
});
