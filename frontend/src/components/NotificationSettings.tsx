import { useCallback, useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { api, unwrap } from "../api/client";
import type { components } from "../api/schema";
import { problemMessage } from "../lib/problem";
import { formatDateTime } from "../lib/format";
import {
  currentSubscription,
  disablePush,
  enablePush,
  pushPermission,
  pushSupport,
  storedDeviceId,
} from "../push/push";
import { ErrorBox, InfoBox } from "./common";
import { Switch } from "./ui";

type Config = components["schemas"]["PushConfigDto"];
type Device = components["schemas"]["PushDeviceDto"];
type Preferences = components["schemas"]["PushPreferencesDto"];
type Category = Preferences["categories"][number];

const CATEGORIES: Category[] = ["TASKS", "DEADLINES", "REVIEW", "SYSTEM"];
const DEFAULT_QUIET = { start: "22:00", end: "07:00" };

/** "22:00:00" from the API as "22:00" for a time input, and back. */
const hhmm = (time: string | null | undefined) => (time ? time.substring(0, 5) : "");

/**
 * Push notifications (issue #28): this device on or off, a test push, what is pushed to all of the member's devices,
 * and the member's other devices. The in-app inbox stays complete either way.
 */
export function NotificationSettings() {
  const { t, i18n } = useTranslation();
  const support = pushSupport();
  const [config, setConfig] = useState<Config | null>(null);
  const [devices, setDevices] = useState<Device[]>([]);
  const [preferences, setPreferences] = useState<Preferences | null>(null);
  const [thisDevice, setThisDevice] = useState<string | null>(null);
  const [permission, setPermission] = useState(pushPermission);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);

  const load = useCallback(async () => {
    const [loadedConfig, loadedDevices, loadedPreferences] = await Promise.all([
      api.GET("/api/push/config").then(unwrap),
      api.GET("/api/push/devices").then(unwrap),
      api.GET("/api/push/preferences").then(unwrap),
    ]);
    setConfig(loadedConfig);
    setDevices(loadedDevices);
    setPreferences(loadedPreferences);
    const subscribed = (await currentSubscription()) !== null;
    const stored = storedDeviceId();
    setThisDevice(subscribed && stored && loadedDevices.some((device) => device.id === stored) ? stored : null);
    setPermission(pushPermission());
  }, []);

  useEffect(() => {
    load().catch((failure) => setError(problemMessage(t, failure)));
  }, [load, t]);

  const act = async (action: () => Promise<string | null>) => {
    setBusy(true);
    setError(null);
    setMessage(null);
    try {
      setMessage(await action());
    } catch (failure) {
      setError(problemMessage(t, failure));
    } finally {
      setBusy(false);
      await load().catch(() => undefined);
    }
  };

  const toggleThisDevice = (on: boolean) =>
    act(async () => {
      if (!on) {
        await disablePush(thisDevice);
        return t("push.device.off");
      }
      if (!config?.publicKey) return null;
      const outcome = await enablePush(config.publicKey);
      if (outcome === "denied") return t("push.device.blocked");
      if (outcome === "noWorker") return t("push.device.noWorker");
      return t("push.device.on");
    });

  const sendTest = (id: string) =>
    act(async () => {
      const result = unwrap(await api.POST("/api/push/devices/{id}/test", { params: { path: { id } } }));
      return t(`push.test.${result.outcome}`);
    });

  const remove = (id: string) =>
    act(async () => {
      if (id === thisDevice) {
        await disablePush(id);
      } else {
        unwrap(await api.DELETE("/api/push/devices/{id}", { params: { path: { id } } }));
      }
      return t("push.device.removed");
    });

  const change = (next: Partial<Preferences>) => {
    if (!preferences) return;
    const body = { ...preferences, ...next };
    setPreferences(body);
    setBusy(true);
    setError(null);
    setMessage(null);
    api
      .PUT("/api/push/preferences", { body })
      .then((result) => setPreferences(unwrap(result)))
      .catch(async (failure) => {
        setError(problemMessage(t, failure));
        // After a conflict the preferences saved on the other device are shown, with their current version.
        await load().catch(() => undefined);
      })
      .finally(() => setBusy(false));
  };

  const toggleCategory = (category: Category, on: boolean) => {
    if (!preferences) return;
    const categories = on
      ? [...new Set([...preferences.categories, category])]
      : preferences.categories.filter((c) => c !== category);
    change({ categories });
  };

  const quiet = preferences?.quietStart != null;
  const deviceState = (): string => {
    if (support === "unsupported") return t("push.device.unsupported");
    if (support === "insecure") return t("push.device.insecure");
    if (config && !config.available) return t("push.device.serverUnavailable");
    if (permission === "denied") return t("push.device.blocked");
    return thisDevice ? t("push.device.onHint") : t("push.device.offHint");
  };
  const canEnable = support === "supported" && !!config?.available && permission !== "denied";
  const others = devices.filter((device) => device.id !== thisDevice);

  return (
    <section aria-labelledby="notifications" className="flex flex-col gap-3" data-testid="notification-settings">
      <h2 id="notifications" className="ui-sec">
        {t("push.title")}
      </h2>
      <Switch
        checked={thisDevice !== null}
        onChange={(on) => void toggleThisDevice(on)}
        description={deviceState()}
        disabled={busy || (thisDevice === null && !canEnable)}
      >
        {t("push.device.label")}
      </Switch>
      {thisDevice && (
        <div>
          <button type="button" className="btn-secondary" disabled={busy} onClick={() => void sendTest(thisDevice)} data-testid="push-test">
            {t("push.test.send")}
          </button>
        </div>
      )}
      <ErrorBox message={error} />
      {message && <InfoBox>{message}</InfoBox>}
      {preferences && (
        <div className="flex flex-col gap-1" data-testid="push-preferences">
          <p className="text-[15px] font-semibold">{t("push.categories")}</p>
          {CATEGORIES.map((category) => (
            <Switch
              key={category}
              checked={preferences.categories.includes(category)}
              onChange={(on) => toggleCategory(category, on)}
              description={t(`push.category.${category}.hint`)}
              disabled={busy}
            >
              {t(`push.category.${category}.label`)}
            </Switch>
          ))}
          <Switch checked={preferences.showDetails} onChange={(showDetails) => change({ showDetails })} description={t("push.detailsHint")} disabled={busy}>
            {t("push.details")}
          </Switch>
          <Switch
            checked={quiet}
            onChange={(on) =>
              change(on ? { quietStart: DEFAULT_QUIET.start, quietEnd: DEFAULT_QUIET.end } : { quietStart: null, quietEnd: null })
            }
            description={t("push.quiet.hint")}
            disabled={busy}
          >
            {t("push.quiet.label")}
          </Switch>
          {quiet && (
            <div className="flex gap-3 pb-2">
              <label className="flex-1">
                <span className="label">{t("push.quiet.from")}</span>
                <input
                  type="time"
                  className="field"
                  value={hhmm(preferences.quietStart)}
                  disabled={busy}
                  onChange={(event) => event.target.value && change({ quietStart: event.target.value })}
                />
              </label>
              <label className="flex-1">
                <span className="label">{t("push.quiet.until")}</span>
                <input
                  type="time"
                  className="field"
                  value={hhmm(preferences.quietEnd)}
                  disabled={busy}
                  onChange={(event) => event.target.value && change({ quietEnd: event.target.value })}
                />
              </label>
            </div>
          )}
        </div>
      )}
      {others.length > 0 && (
        <div className="border-t border-line" data-testid="push-devices">
          <p className="pt-3 text-[15px] font-semibold">{t("push.otherDevices")}</p>
          {others.map((device) => (
            <div key={device.id} className="ui-row">
              <span className="ui-row-text">
                <b>{device.deviceLabel ?? t("push.unknownDevice")}</b>
                <span>
                  {t(`push.state.${device.state}`)}
                  {device.lastSentAt && ` · ${t("push.lastSent", { time: formatDateTime(device.lastSentAt, i18n.language) })}`}
                </span>
              </span>
              <button type="button" className="btn-ghost" disabled={busy} onClick={() => void remove(device.id)}>
                {t("push.remove")}
              </button>
            </div>
          ))}
        </div>
      )}
    </section>
  );
}
