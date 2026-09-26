// @vitest-environment jsdom
import { useState } from "react";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { nextSegmentIndex, SegmentedControl } from "./SegmentedControl";

afterEach(cleanup);

function Palette() {
  const [value, setValue] = useState<"a" | "b" | "c">("a");
  return (
    <SegmentedControl
      label="Palette"
      value={value}
      onChange={setValue}
      options={[
        { value: "a", label: "Streetkid" },
        { value: "b", label: "Corpo" },
        { value: "c", label: "Nomad" },
      ]}
    />
  );
}

describe("nextSegmentIndex", () => {
  it("wraps arrow keys around both ends and jumps with Home and End", () => {
    expect(nextSegmentIndex("ArrowRight", 2, 3)).toBe(0);
    expect(nextSegmentIndex("ArrowLeft", 0, 3)).toBe(2);
    expect(nextSegmentIndex("ArrowDown", 0, 3)).toBe(1);
    expect(nextSegmentIndex("Home", 2, 3)).toBe(0);
    expect(nextSegmentIndex("End", 0, 3)).toBe(2);
    expect(nextSegmentIndex("Enter", 1, 3)).toBeNull();
  });
});

describe("SegmentedControl", () => {
  it("is a radio group with exactly one checked option and one tab stop", () => {
    render(<Palette />);
    expect(screen.getByRole("radiogroup", { name: "Palette" })).toBeTruthy();
    const radios = screen.getAllByRole("radio");
    expect(radios.map((radio) => radio.getAttribute("aria-checked"))).toEqual(["true", "false", "false"]);
    expect(radios.map((radio) => radio.tabIndex)).toEqual([0, -1, -1]);
  });

  it("moves selection and focus with the arrow keys", () => {
    render(<Palette />);
    const [first] = screen.getAllByRole("radio");
    first.focus();
    fireEvent.keyDown(first, { key: "ArrowLeft" });
    const nomad = screen.getByRole("radio", { name: "Nomad" });
    expect(nomad.getAttribute("aria-checked")).toBe("true");
    expect(document.activeElement).toBe(nomad);
    fireEvent.keyDown(nomad, { key: "ArrowRight" });
    expect(screen.getByRole("radio", { name: "Streetkid" }).getAttribute("aria-checked")).toBe("true");
  });

  it("selects an option on click", () => {
    render(<Palette />);
    fireEvent.click(screen.getByRole("radio", { name: "Corpo" }));
    expect(screen.getByRole("radio", { name: "Corpo" }).getAttribute("aria-checked")).toBe("true");
    expect(screen.getByRole("radio", { name: "Streetkid" }).tabIndex).toBe(-1);
  });
});
