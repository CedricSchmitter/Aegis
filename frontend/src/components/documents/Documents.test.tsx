// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { CategoryDto, CategoryListResponse, DocumentDto, LabelDto } from "../../api/types";
import i18n from "../../i18n";
import { EMPTY_FILTERS } from "../../lib/catalogSearch";
import { ActiveFilters } from "../FacetFilters";
import { FolderTree } from "../FolderTree";
import { DocumentTable } from "./DocumentRows";
import { FolderRail } from "./FolderRail";

afterEach(cleanup);

const folder = (id: string, parentId: string | null, name: string, documentCount: number): CategoryDto => ({
  id,
  parentId,
  name,
  impliedLabelIds: [],
  impliedSubjects: [],
  documentCount,
  version: 1,
});

const categories = [folder("house", null, "Haus", 1), folder("bills", "house", "Rechnungen", 2), folder("car", null, "Auto", 0)];
const catalog = { categories, unsortedCount: 4, totalDocuments: 7 } as CategoryListResponse;

const document = (id: string, title: string) =>
  ({
    id,
    title,
    contentType: "application/pdf",
    archivePdfAvailable: false,
    uploadedAt: "2026-09-20T10:00:00Z",
    processing: { state: "ARCHIVED" },
    analysis: { state: "NOT_REQUESTED" },
    textRecognition: { state: "RECOGNIZED" },
    classification: { labels: [], path: [{ id: "house", name: "Haus" }], placement: { origin: "MANUAL" }, placementSuggestion: null },
  }) as unknown as DocumentDto;

describe("folder rail", () => {
  it("offers All, Unsorted and the top level with counts, and manages folders at the end", async () => {
    await i18n.changeLanguage("de");
    const onSelect = vi.fn();
    render(
      <MemoryRouter>
        <FolderRail catalog={catalog} selected={null} onSelect={onSelect} />
      </MemoryRouter>,
    );
    const rail = screen.getByRole("group", { name: "Ordner" });
    expect(within(rail).getAllByRole("button").map((pill) => pill.textContent)).toEqual(["Alle7", "Unsortiert4", "Auto0", "Haus3"]);
    expect(screen.getByTestId("folder-all").getAttribute("aria-pressed")).toBe("true");
    expect(within(rail).getByRole("link", { name: "Ordner verwalten" }).getAttribute("href")).toBe("/documents/folders");
    fireEvent.click(screen.getByTestId("folder-house"));
    expect(onSelect).toHaveBeenCalledWith("house");
  });

  it("shows the subfolders of a chosen folder", async () => {
    await i18n.changeLanguage("de");
    render(
      <MemoryRouter>
        <FolderRail catalog={catalog} selected="house" onSelect={vi.fn()} />
      </MemoryRouter>,
    );
    expect(screen.queryByTestId("folder-unsorted")).toBeNull();
    expect(screen.getByTestId("folder-bills").textContent).toBe("Rechnungen2");
  });
});

describe("sidebar folder tree", () => {
  it("moves with the arrow keys, expands subfolders and chooses with Enter", async () => {
    await i18n.changeLanguage("de");
    const onSelect = vi.fn();
    render(<FolderTree categories={categories} unsortedCount={4} selected={null} onSelect={onSelect} />);
    const items = () => screen.getAllByRole("treeitem").map((item) => item.getAttribute("data-folder"));
    expect(items()).toEqual(["car", "house", "unsorted"]);
    fireEvent.keyDown(screen.getByTestId("folder-house"), { key: "ArrowRight" });
    expect(items()).toEqual(["car", "house", "bills", "unsorted"]);
    expect(screen.getByTestId("folder-house").getAttribute("aria-expanded")).toBe("true");
    fireEvent.keyDown(screen.getByTestId("folder-bills"), { key: "Enter" });
    expect(onSelect).toHaveBeenCalledWith("bills");
  });
});

describe("document table", () => {
  const renderTable = (onPreview: ((id: string) => void) | null) =>
    render(
      <MemoryRouter initialEntries={["/documents"]}>
        <Routes>
          <Route
            path="/documents"
            element={
              <DocumentTable
                items={[document("d1", "Stromvertrag"), document("d2", "Grundsteuer")]}
                selection={[]}
                onSelectionChange={vi.fn()}
                previewed="d1"
                onPreview={onPreview}
              />
            }
          />
          <Route path="/documents/:id" element={<p>opened</p>} />
        </Routes>
      </MemoryRouter>,
    );

  it("previews on a mouse click while the context panel is shown and opens on Enter", async () => {
    await i18n.changeLanguage("de");
    const onPreview = vi.fn();
    renderTable(onPreview);
    expect(screen.getAllByTestId("document-card")[0].getAttribute("data-selected")).toBe("true");
    fireEvent.click(screen.getByRole("link", { name: "Grundsteuer" }), { detail: 1 });
    expect(onPreview).toHaveBeenCalledWith("d2");
    expect(screen.queryByText("opened")).toBeNull();
    // Enter on a link is a click without a mouse (detail 0): it opens the document.
    fireEvent.click(screen.getByRole("link", { name: "Grundsteuer" }), { detail: 0 });
    expect(screen.getByText("opened")).toBeTruthy();
  });

  it("opens the document on a click without the context panel", async () => {
    await i18n.changeLanguage("de");
    renderTable(null);
    expect(screen.getAllByTestId("document-card")[0].getAttribute("data-selected")).toBe("false");
    fireEvent.click(screen.getByRole("link", { name: "Stromvertrag" }), { detail: 1 });
    expect(screen.getByText("opened")).toBeTruthy();
  });
});

describe("active filters", () => {
  it("names each chosen value and removes it alone", async () => {
    await i18n.changeLanguage("de");
    const labels = [
      { id: "l1", facet: "ORGANIZATION", name: "Stadtwerke" },
      { id: "l2", facet: "TOPIC", name: "Strom" },
    ] as unknown as LabelDto[];
    const onChange = vi.fn();
    render(
      <ActiveFilters
        filters={{ ...EMPTY_FILTERS, organization: ["l1"], topic: ["l2"], missing: ["TAG"], page: 2 }}
        labels={labels}
        members={[]}
        onChange={onChange}
      />,
    );
    const list = screen.getByRole("list", { name: "Aktive Filter" });
    expect(within(list).getAllByRole("listitem").map((item) => item.textContent)).toEqual(["OrgStadtwerke", "ThemaStrom", "StichwortOhne Angabe"]);
    fireEvent.click(screen.getByRole("button", { name: "Org Stadtwerke entfernen" }));
    expect(onChange).toHaveBeenCalledWith(expect.objectContaining({ organization: [], topic: ["l2"], missing: ["TAG"], page: 0 }));
  });
});
