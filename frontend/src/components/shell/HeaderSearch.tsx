import { useEffect, useRef, useState, type FormEvent } from "react";
import { useLocation, useNavigate, useSearchParams } from "react-router";
import { useTranslation } from "react-i18next";
import { SearchField } from "../ui";

/** Ctrl+K (Cmd+K on a Mac) moves the focus into the search from anywhere in the desktop shell. */
function isSearchShortcut(event: KeyboardEvent): boolean {
  return (event.ctrlKey || event.metaKey) && !event.altKey && !event.shiftKey && event.key.toLowerCase() === "k";
}

/** The document search of the desktop header; it opens the document list with the query (issue #27). */
export function HeaderSearch() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const { pathname } = useLocation();
  const [params, setParams] = useSearchParams();
  const input = useRef<HTMLInputElement>(null);
  const listQuery = pathname === "/documents" ? (params.get("query") ?? "") : "";
  const [text, setText] = useState(listQuery);

  // The field shows what the document list is searching for, and empties when the member leaves the list.
  useEffect(() => setText(listQuery), [listQuery]);

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (!isSearchShortcut(event)) return;
      event.preventDefault();
      input.current?.focus();
      input.current?.select();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);

  // Emptying the field (the clear button or deleting the text) drops the search at once; the other filters stay.
  const change = (value: string) => {
    setText(value);
    if (value.trim() || !listQuery) return;
    setParams((current) => {
      const next = new URLSearchParams(current);
      next.delete("query");
      next.delete("page");
      return next;
    });
  };

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const query = text.trim();
    navigate(query ? `/documents?${new URLSearchParams({ query })}` : "/documents");
  };

  return (
    <form role="search" className="w-full max-w-[520px]" onSubmit={submit} data-testid="header-search">
      <SearchField
        ref={input}
        label={t("shell.search.label")}
        placeholder={t("shell.search.label")}
        shortcut={t("shell.search.shortcut")}
        value={text}
        onChange={(event) => change(event.target.value)}
        enterKeyHint="search"
      />
    </form>
  );
}
