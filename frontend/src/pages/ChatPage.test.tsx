// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import i18n from "../i18n";
import { ChatPage } from "./ChatPage";

const api = vi.hoisted(() => ({ GET: vi.fn(), POST: vi.fn(), PUT: vi.fn(), DELETE: vi.fn() }));
vi.mock("../api/client", async (original) => ({ ...(await original<typeof import("../api/client")>()), api }));

vi.mock("../auth/SessionContext", () => ({
  useSession: () => ({ member: { locale: "DE" } }),
}));

beforeEach(async () => {
  await i18n.changeLanguage("de");
});
afterEach(() => {
  cleanup();
  vi.resetAllMocks();
});

const ok = (data: unknown) => ({ data, response: new Response(null, { status: 200 }) });
const summary = (id: string, title: string) => ({ id, title, createdAt: "2026-10-02T17:15:00Z", updatedAt: "2026-10-02T17:15:00Z", questions: 1 });
const recent = [summary("c1", "Wie hoch war meine Grunderwerbsteuer"), summary("c2", "Wann war das letzte MRT")];
const older = summary("c9", "Wo war nochmal das Hotel in Frankfurt?");

function answer(search: (q: string) => unknown[] = () => []) {
  api.GET.mockImplementation((path: string, init?: { params?: { query?: { q?: string } } }) => {
    if (path === "/api/chat/conversations") return Promise.resolve(ok({ available: true, unavailableReason: null, conversations: recent }));
    if (path === "/api/chat/conversations/search") return Promise.resolve(ok({ conversations: search(init?.params?.query?.q ?? "") }));
    return Promise.resolve(ok(null));
  });
}

function renderPage() {
  render(
    <MemoryRouter initialEntries={["/chat"]}>
      <Routes>
        <Route path="/chat" element={<ChatPage />} />
        <Route path="/chat/:id" element={<ChatPage />} />
      </Routes>
    </MemoryRouter>,
  );
}

const history = () => screen.getByTestId("chat-history") as HTMLDialogElement;

describe("earlier questions", () => {
  it("stay in a closed side panel until the member opens it", async () => {
    answer();
    renderPage();

    const open = await screen.findByTestId("chat-history-open");
    await waitFor(() => expect(open.textContent).toContain("2"));
    expect(history().open).toBe(false);
    expect(open.getAttribute("aria-expanded")).toBe("false");
    expect(screen.getByTestId("chat-composer")).toBeTruthy();

    fireEvent.click(open);

    expect(history().open).toBe(true);
    expect(within(history()).getByRole("heading", { name: "Verlauf" })).toBeTruthy();
    expect(within(history()).getByText("Wann war das letzte MRT")).toBeTruthy();
  });

  it("are searched on the server, so older ones beyond the recent list are found too", async () => {
    answer((q) => (q === "hotel frankfurt" ? [older] : []));
    renderPage();
    fireEvent.click(await screen.findByTestId("chat-history-open"));

    fireEvent.change(within(history()).getByTestId("chat-search"), { target: { value: "  hotel frankfurt " } });

    expect(await within(history()).findByText("Wo war nochmal das Hotel in Frankfurt?")).toBeTruthy();
    expect(within(history()).queryByText("Wann war das letzte MRT")).toBeNull();
    expect(api.GET).toHaveBeenCalledWith("/api/chat/conversations/search", { params: { query: { q: "hotel frankfurt" } } });
  });

  it("say so when nothing matches and come back when the search is cleared", async () => {
    answer();
    renderPage();
    fireEvent.click(await screen.findByTestId("chat-history-open"));
    const search = within(history()).getByTestId("chat-search");

    fireEvent.change(search, { target: { value: "Zahnarzt" } });
    expect((await within(history()).findByTestId("chat-history-empty")).textContent).toBe("Keine Frage oder Antwort enthält „Zahnarzt“.");

    fireEvent.change(search, { target: { value: "" } });
    expect(within(history()).getByText("Wann war das letzte MRT")).toBeTruthy();
  });

  it("open a picked conversation and close the panel", async () => {
    answer();
    renderPage();
    fireEvent.click(await screen.findByTestId("chat-history-open"));

    fireEvent.click(within(history()).getByText("Wann war das letzte MRT"));

    await waitFor(() => expect(api.GET).toHaveBeenCalledWith("/api/chat/conversations/{id}", { params: { path: { id: "c2" } } }));
    expect(history().open).toBe(false);
    expect(screen.getByTestId("chat-new")).toBeTruthy();
  });
});
