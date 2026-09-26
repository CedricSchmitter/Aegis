import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { api, onUnauthenticated, unwrap } from "../api/client";
import type { AppearanceDto, MemberDto, ProductLocale } from "../api/types";
import { applyLocale } from "../i18n";
import { applyAppearance, DEFAULT_APPEARANCE, fromApi, toApi, type Appearance } from "../lib/appearance";

interface SessionState {
  member: MemberDto | null;
  /** Every member, including the assistant account; for task responsibility and names in history. */
  members: MemberDto[];
  /** The persons of the household: the only members a document or folder can be about. */
  persons: MemberDto[];
  /** The member's own palette and effects (ADR-0019); Streetkid with effects on while nobody is signed in. */
  appearance: Appearance;
  loading: boolean;
  login(username: string, password: string): Promise<void>;
  logout(): Promise<void>;
  changeLocale(locale: ProductLocale): Promise<void>;
  /** Applies the choice at once and stores it for the member; on failure the stored look comes back and it throws. */
  changeAppearance(appearance: Appearance): Promise<void>;
  memberName(id: string | null | undefined): string | null;
  refreshMembers(): Promise<void>;
}

const SessionContext = createContext<SessionState | null>(null);

export function SessionProvider({ children }: { children: ReactNode }) {
  const [member, setMember] = useState<MemberDto | null>(null);
  const [members, setMembers] = useState<MemberDto[]>([]);
  const [appearance, setAppearance] = useState<Appearance>(DEFAULT_APPEARANCE);
  const [loading, setLoading] = useState(true);

  const refreshMembers = useCallback(async () => {
    const list = unwrap(await api.GET("/api/members"));
    setMembers(list);
  }, []);

  // What the server holds for the member; a failed save goes back to it.
  const storedAppearance = useRef<Appearance>(DEFAULT_APPEARANCE);

  /** The look follows the member like the locale does, applied as soon as the session is known. */
  const showAppearance = useCallback((next: Appearance) => {
    applyAppearance(document.documentElement, next);
    setAppearance(next);
  }, []);

  const startSession = useCallback(
    (session: { member: MemberDto; appearance?: AppearanceDto }) => {
      setMember(session.member);
      applyLocale(session.member.locale);
      storedAppearance.current = fromApi(session.appearance);
      showAppearance(storedAppearance.current);
    },
    [showAppearance],
  );

  const endSession = useCallback(() => {
    setMember(null);
    setMembers([]);
    // The next person on this device starts from the default until their own session says otherwise.
    storedAppearance.current = DEFAULT_APPEARANCE;
    showAppearance(DEFAULT_APPEARANCE);
  }, [showAppearance]);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const session = unwrap(await api.GET("/api/auth/session"));
        if (cancelled) return;
        startSession(session);
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
  }, [refreshMembers, startSession]);

  useEffect(() => onUnauthenticated(endSession), [endSession]);

  const login = useCallback(
    async (username: string, password: string) => {
      const session = unwrap(await api.POST("/api/auth/login", { body: { username, password } }));
      startSession(session);
      await refreshMembers();
    },
    [refreshMembers, startSession],
  );

  const logout = useCallback(async () => {
    await api.POST("/api/auth/logout");
    endSession();
  }, [endSession]);

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

  // Arrow keys in the palette group save on every step; only the latest answer may set the look.
  const latestChange = useRef(0);
  const changeAppearance = useCallback(
    async (next: Appearance) => {
      const request = ++latestChange.current;
      showAppearance(next);
      try {
        const stored = fromApi(unwrap(await api.PUT("/api/members/me/appearance", { body: toApi(next) })));
        if (request === latestChange.current) {
          storedAppearance.current = stored;
          showAppearance(stored);
        }
      } catch (failure) {
        if (request === latestChange.current) showAppearance(storedAppearance.current);
        throw failure;
      }
    },
    [showAppearance],
  );

  const memberName = useCallback(
    (id: string | null | undefined) => (id ? (members.find((m) => m.id === id)?.displayName ?? null) : null),
    [members],
  );

  const persons = useMemo(() => members.filter((entry) => entry.kind === "PERSON"), [members]);

  const value = useMemo<SessionState>(
    () => ({
      member,
      members,
      persons,
      appearance,
      loading,
      login,
      logout,
      changeLocale,
      changeAppearance,
      memberName,
      refreshMembers,
    }),
    [member, members, persons, appearance, loading, login, logout, changeLocale, changeAppearance, memberName, refreshMembers],
  );
  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
}

export function useSession(): SessionState {
  const context = useContext(SessionContext);
  if (!context) throw new Error("useSession must be used within SessionProvider");
  return context;
}
