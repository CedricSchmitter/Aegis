// @vitest-environment jsdom
import { useState } from "react";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { Checkbox, Switch } from "./Toggles";

afterEach(cleanup);

function Effects() {
  const [on, setOn] = useState(true);
  return (
    <Switch checked={on} onChange={setOn} description="Grid and scanlines">
      Effects
    </Switch>
  );
}

describe("Switch", () => {
  it("exposes its state as aria-checked and toggles on click", () => {
    render(<Effects />);
    const toggle = screen.getByRole("switch", { name: "Effects" });
    expect(toggle.getAttribute("aria-checked")).toBe("true");
    expect(toggle.getAttribute("aria-describedby")).toBeTruthy();
    fireEvent.click(toggle);
    expect(toggle.getAttribute("aria-checked")).toBe("false");
  });
});

describe("Checkbox", () => {
  it("is labelled by its text so the whole row toggles it", () => {
    render(<Checkbox defaultChecked={false}>Notify the reviewer</Checkbox>);
    const box = screen.getByRole("checkbox", { name: "Notify the reviewer" }) as HTMLInputElement;
    fireEvent.click(screen.getByText("Notify the reviewer"));
    expect(box.checked).toBe(true);
  });
});
