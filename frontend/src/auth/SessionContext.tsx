import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import { api, onUnauthenticated, unwrap } from "../api/client";
import type { MemberDto, ProductLocale } from "../api/types";
import { applyLocale } from "../i18n";

interface SessionState {
  member: MemberDto | null;
  /** Every member, including the assistant account; for task responsibility and names in history. */
  members: MemberDto[];
  /** The persons of the household: the only members a document or folder can be about. */
  persons: MemberDto[];
  loading: boolean;
  login(username: string, password: string): Promise<void>;
  logout(): Promise<void>;
  changeLocale(locale: ProductLocale): Promise<void>;
  memberName(id: string | null | undefined): string | null;
  refreshMembers(): Promise<void>;
}

const SessionContext = createContext<SessionState | null>(null);

export function SessionProvider({ children }: { children: ReactNode }) {
  const [member, setMember] = useState<MemberDto | null>(null);
  const [members, setMembers] = useState<MemberDto[]>([]);
  const [loading, setLoading] = useState(true);

  const refreshMembers = useCallback(async () => {
    const list = unwrap(await api.GET("/api/members"));
    setMembers(list);
  }, []);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const session = unwrap(await api.GET("/api/auth/session"));
        if (cancelled) return;
        setMember(session.member);
        applyLocale(session.member.locale);
        await refreshMembers();
      } catch {
        if (!cancelled) setMember(null);
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [refreshMembers]);

  useEffect(() => onUnauthenticated(() => setMember(null)), []);

  const login = useCallback(
    async (username: string, password: string) => {
      const session = unwrap(await api.POST("/api/auth/login", { body: { username, password } }));
      setMember(session.member);
      applyLocale(session.member.locale);
      await refreshMembers();
    },
    [refreshMembers],
  );

  const logout = useCallback(async () => {
    await api.POST("/api/auth/logout");
    setMember(null);
    setMembers([]);
  }, []);

  const changeLocale = useCallback(
    async (locale: ProductLocale) => {
      applyLocale(locale);
      if (member) {
        const updated = unwrap(await api.PUT("/api/members/me/locale", { body: { locale } }));
        setMember(updated);
      }
    },
    [member],
  );

  const memberName = useCallback(
    (id: string | null | undefined) => (id ? (members.find((m) => m.id === id)?.displayName ?? null) : null),
    [members],
  );

  const persons = useMemo(() => members.filter((entry) => entry.kind === "PERSON"), [members]);

  const value = useMemo<SessionState>(
    () => ({ member, members, persons, loading, login, logout, changeLocale, memberName, refreshMembers }),
    [member, members, persons, loading, login, logout, changeLocale, memberName, refreshMembers],
  );
  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
}

export function useSession(): SessionState {
  const context = useContext(SessionContext);
  if (!context) throw new Error("useSession must be used within SessionProvider");
  return context;
}
