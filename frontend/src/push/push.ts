import { api, unwrap } from "../api/client";

/** Why this browser cannot receive pushes, or that it can (issue #28). */
export type PushSupport = "supported" | "insecure" | "unsupported";

const DEVICE_KEY = "aegis.push.deviceId";

export function pushSupport(): PushSupport {
  if (typeof window === "undefined") return "unsupported";
  if (!("serviceWorker" in navigator) || !("PushManager" in window) || !("Notification" in window)) return "unsupported";
  if (!window.isSecureContext) return "insecure";
  return "supported";
}

export function pushPermission(): NotificationPermission {
  return typeof Notification === "undefined" ? "denied" : Notification.permission;
}

/** The id the server gave this browser's subscription, remembered so the settings can mark "this device". */
export function storedDeviceId(): string | null {
  try {
    return window.localStorage.getItem(DEVICE_KEY);
  } catch {
    return null;
  }
}

function storeDeviceId(id: string | null) {
  try {
    if (id) window.localStorage.setItem(DEVICE_KEY, id);
    else window.localStorage.removeItem(DEVICE_KEY);
  } catch {
    // Without storage the device is still subscribed; only the "this device" mark is lost.
  }
}

/** A short, readable name such as "Android · Samsung Internet"; never sent anywhere but to the member's own list. */
export function deviceLabel(userAgent: string): string {
  const system = /Android/.test(userAgent)
    ? "Android"
    : /iPhone|iPad/.test(userAgent)
      ? "iOS"
      : /Windows/.test(userAgent)
        ? "Windows"
        : /Mac OS X/.test(userAgent)
          ? "macOS"
          : /Linux/.test(userAgent)
            ? "Linux"
            : "";
  const browser = /SamsungBrowser/.test(userAgent)
    ? "Samsung Internet"
    : /Edg\//.test(userAgent)
      ? "Edge"
      : /Firefox\//.test(userAgent)
        ? "Firefox"
        : /Chrome\//.test(userAgent)
          ? "Chrome"
          : /Safari\//.test(userAgent)
            ? "Safari"
            : "";
  return [system, browser].filter(Boolean).join(" · ");
}

function decodeKey(base64url: string): Uint8Array<ArrayBuffer> {
  const base64 = base64url.replace(/-/g, "+").replace(/_/g, "/").padEnd(Math.ceil(base64url.length / 4) * 4, "=");
  const raw = atob(base64);
  const bytes = new Uint8Array(new ArrayBuffer(raw.length));
  for (let i = 0; i < raw.length; i++) bytes[i] = raw.charCodeAt(i);
  return bytes;
}

function sameKey(subscription: PushSubscription, key: Uint8Array): boolean {
  const current = subscription.options.applicationServerKey;
  if (!current) return false;
  const bytes = new Uint8Array(current);
  return bytes.length === key.length && bytes.every((byte, index) => byte === key[index]);
}

async function registration(): Promise<ServiceWorkerRegistration | undefined> {
  return (await navigator.serviceWorker.getRegistration("/")) ?? undefined;
}

/** This browser's push subscription, if it has one. */
export async function currentSubscription(): Promise<PushSubscription | null> {
  if (pushSupport() !== "supported") return null;
  const worker = await registration();
  return worker ? worker.pushManager.getSubscription() : null;
}

async function register(subscription: PushSubscription): Promise<string> {
  const json = subscription.toJSON();
  const device = unwrap(
    await api.POST("/api/push/devices", {
      body: {
        endpoint: subscription.endpoint,
        p256dh: json.keys?.p256dh ?? "",
        auth: json.keys?.auth ?? "",
        deviceLabel: deviceLabel(navigator.userAgent) || null,
      },
    }),
  );
  storeDeviceId(device.id);
  return device.id;
}

export type EnableOutcome = "enabled" | "denied" | "noWorker";

/**
 * Asks for permission, subscribes this browser with the server's key and registers it for the signed-in member. A
 * subscription made for another server key is replaced.
 */
export async function enablePush(publicKey: string): Promise<EnableOutcome> {
  const permission = await Notification.requestPermission();
  if (permission !== "granted") return "denied";
  const worker = await registration();
  if (!worker) return "noWorker";
  const key = decodeKey(publicKey);
  let subscription = await worker.pushManager.getSubscription();
  if (subscription && !sameKey(subscription, key)) {
    await subscription.unsubscribe();
    subscription = null;
  }
  subscription ??= await worker.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: key });
  await register(subscription);
  return "enabled";
}

/** Stops pushes to this browser: removes it on the server and drops the browser's subscription. */
export async function disablePush(deviceId: string | null): Promise<void> {
  if (deviceId) unwrap(await api.DELETE("/api/push/devices/{id}", { params: { path: { id: deviceId } } }));
  storeDeviceId(null);
  const subscription = await currentSubscription();
  await subscription?.unsubscribe();
}

/**
 * Re-registers an existing subscription when the app opens, so the server knows the browser's current endpoint and
 * member (for example after the other member signed in on this browser). Failures are ignored: the settings show the
 * state and offer to switch pushes on again.
 */
export async function syncPush(publicKey: string): Promise<void> {
  if (pushPermission() !== "granted") return;
  const subscription = await currentSubscription();
  if (!subscription || !sameKey(subscription, decodeKey(publicKey))) return;
  await register(subscription);
}

/** Once per app start: keeps an existing subscription registered for the signed-in member. */
export async function syncPushOnStart(): Promise<void> {
  if (pushSupport() !== "supported" || pushPermission() !== "granted") return;
  const config = unwrap(await api.GET("/api/push/config"));
  if (config.available && config.publicKey) await syncPush(config.publicKey);
}
