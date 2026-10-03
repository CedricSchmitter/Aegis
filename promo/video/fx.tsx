import { useEffect, useState, type CSSProperties, type ReactNode } from "react";
import {
  AbsoluteFill,
  continueRender,
  delayRender,
  interpolate,
  OffthreadVideo,
  random,
  spring,
  staticFile,
  useCurrentFrame,
  useVideoConfig,
  Img,
} from "remotion";

export const C = {
  void: "#07080b",
  surface: "#0c0e13",
  line: "#2b3140",
  text: "#e8ecf1",
  text2: "#c9d1dd",
  text3: "#a3adbd",
  yellow: "#fcee0a",
  cyan: "#00f0ff",
  red: "#ff2a6d",
};
export const DISPLAY = "'Chakra Petch', sans-serif";
export const MONO = "'Share Tech Mono', monospace";

const FONTS: [string, string, string][] = [
  ["Chakra Petch", "chakra-petch-latin-500-normal.woff2", "500"],
  ["Chakra Petch", "chakra-petch-latin-600-normal.woff2", "600"],
  ["Chakra Petch", "chakra-petch-latin-700-normal.woff2", "700"],
  ["Share Tech Mono", "share-tech-mono-latin-400-normal.woff2", "400"],
];

/** Loads the app's own fonts before the first frame renders. */
export function useFonts() {
  const [handle] = useState(() => delayRender("fonts"));
  useEffect(() => {
    Promise.all(
      FONTS.map(([family, file, weight]) => {
        const face = new FontFace(family, `url(${staticFile(file)})`, { weight });
        document.fonts.add(face);
        return face.load();
      }),
    ).then(() => continueRender(handle));
  }, [handle]);
}

const clamp = { extrapolateLeft: "clamp", extrapolateRight: "clamp" } as const;
export const fade = (frame: number, from: number, to: number, a = 0, b = 1) => interpolate(frame, [from, to], [a, b], clamp);

/** Neon perspective grid floor with a horizon glow; it scrolls towards the viewer. */
export function Background({ hue = C.cyan, intensity: base = 1 }: { hue?: string; intensity?: number }) {
  const frame = useCurrentFrame();
  const beat = useBeat();
  const intensity = base * (0.8 + 0.45 * beat);
  const offset = (frame * 6) % 80;
  return (
    <AbsoluteFill style={{ background: C.void, overflow: "hidden" }}>
      <AbsoluteFill
        style={{
          background: `radial-gradient(ellipse 70% 45% at 50% 62%, ${hue}22 0%, transparent 70%),
            radial-gradient(ellipse 40% 30% at 85% 10%, ${C.red}18 0%, transparent 70%)`,
          opacity: intensity,
        }}
      />
      <div
        style={{
          position: "absolute",
          left: "-50%",
          width: "200%",
          top: "58%",
          height: "90%",
          transform: "perspective(500px) rotateX(72deg)",
          transformOrigin: "top center",
          backgroundImage: `linear-gradient(${hue}55 2px, transparent 2px), linear-gradient(90deg, ${hue}55 2px, transparent 2px)`,
          backgroundSize: "80px 80px",
          backgroundPosition: `0 ${offset}px`,
          maskImage: "linear-gradient(to bottom, transparent 0%, black 25%, black 60%, transparent 100%)",
          opacity: 0.55 * intensity,
        }}
      />
      <div
        style={{
          position: "absolute",
          left: 0,
          right: 0,
          top: "58%",
          height: 2,
          background: `linear-gradient(90deg, transparent, ${hue}, transparent)`,
          boxShadow: `0 0 30px 6px ${hue}66`,
          opacity: 0.7 * intensity,
        }}
      />
    </AbsoluteFill>
  );
}

/** Scanlines, vignette and film noise over everything. */
export function Overlay() {
  const frame = useCurrentFrame();
  return (
    <AbsoluteFill style={{ pointerEvents: "none" }}>
      <AbsoluteFill
        style={{
          backgroundImage: "repeating-linear-gradient(0deg, rgba(0,0,0,.22) 0px, rgba(0,0,0,.22) 1px, transparent 1px, transparent 4px)",
          opacity: 0.55,
        }}
      />
      <AbsoluteFill style={{ background: "radial-gradient(ellipse at center, transparent 55%, rgba(0,0,0,.65) 100%)" }} />
      <AbsoluteFill style={{ opacity: 0.07, mixBlendMode: "screen" }}>
        <svg width="1920" height="1080">
          <filter id="n">
            <feTurbulence type="fractalNoise" baseFrequency="0.9" numOctaves="2" seed={frame % 12} />
          </filter>
          <rect width="1920" height="1080" filter="url(#n)" />
        </svg>
      </AbsoluteFill>
    </AbsoluteFill>
  );
}

const GLYPHS = "!<>-_\\/[]{}=+*^?#ABCDEFGHKXYZ0123456789";

/** Text that decodes from random glyphs into its final characters, with an RGB split while it settles. */
export function DecodeText({
  text,
  start = 0,
  speed = 1.4,
  style,
  split = true,
}: {
  text: string;
  start?: number;
  speed?: number;
  style?: CSSProperties;
  split?: boolean;
}) {
  const frame = useCurrentFrame();
  const t = frame - start;
  if (t < 0) return <span style={{ ...style, opacity: 0 }}>{text}</span>;
  const shown = [...text]
    .map((ch, i) => {
      if (ch === " " || t > i * speed + 6) return ch;
      if (t < i * speed) return " ";
      return GLYPHS[Math.floor(random(`${text}${i}${frame}`) * GLYPHS.length)];
    })
    .join("");
  const settle = interpolate(t, [0, text.length * speed + 10], [1, 0], clamp);
  const jitter = (seed: string) => (random(seed + frame) - 0.5) * 14 * settle;
  if (!split) return <span style={style}>{shown}</span>;
  return (
    <span style={{ position: "relative", display: "inline-block", whiteSpace: "pre", ...style }}>
      <span style={{ position: "absolute", left: jitter("r"), top: jitter("ry") * 0.3, color: C.red, opacity: 0.8 * settle, mixBlendMode: "screen" }}>{shown}</span>
      <span style={{ position: "absolute", left: jitter("c"), top: jitter("cy") * 0.3, color: C.cyan, opacity: 0.8 * settle, mixBlendMode: "screen" }}>{shown}</span>
      <span style={{ position: "relative" }}>{shown}</span>
    </span>
  );
}

/** A full-frame glitch burst around a cut: colour bars, slice offsets and a flash. */
export function GlitchBurst({ at, length = 10 }: { at: number; length?: number }) {
  const frame = useCurrentFrame();
  const t = frame - at;
  if (t < -length / 2 || t > length / 2) return null;
  const strength = 1 - Math.abs(t) / (length / 2);
  const bars = Array.from({ length: 9 }, (_, i) => {
    const y = random(`y${i}${frame}`) * 1080;
    const h = 4 + random(`h${i}${frame}`) * 60;
    const x = (random(`x${i}${frame}`) - 0.5) * 300;
    const color = [C.cyan, C.red, C.yellow, "#ffffff"][i % 4];
    return (
      <div
        key={i}
        style={{ position: "absolute", left: x, top: y, width: 1920, height: h, background: color, opacity: 0.25 * strength + 0.1, mixBlendMode: "screen" }}
      />
    );
  });
  return (
    <AbsoluteFill style={{ pointerEvents: "none" }}>
      {bars}
      <AbsoluteFill style={{ background: "#fff", opacity: t === 0 ? 0.35 : t === 1 ? 0.12 : 0 }} />
    </AbsoluteFill>
  );
}

/** Shakes and skews its content around glitch moments. */
export function Shake({ moments, children }: { moments: number[]; children: ReactNode }) {
  const frame = useCurrentFrame();
  const near = moments.map((m) => Math.abs(frame - m)).reduce((a, b) => Math.min(a, b), 99);
  const s = near < 5 ? 1 - near / 5 : 0;
  const x = (random(`sx${frame}`) - 0.5) * 40 * s;
  const skew = (random(`sk${frame}`) - 0.5) * 8 * s;
  return <AbsoluteFill style={{ transform: `translateX(${x}px) skewX(${skew}deg)`, filter: s > 0.5 ? "hue-rotate(40deg) saturate(1.6)" : undefined }}>{children}</AbsoluteFill>;
}

/** Corner brackets and readouts framing the picture like a HUD. */
export function Hud({ label }: { label: string }) {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const seconds = frame / fps;
  const code = `T+00:${String(Math.floor(seconds)).padStart(2, "0")}:${String(Math.floor((seconds % 1) * 100)).padStart(2, "0")}`;
  const corner = (style: CSSProperties) => (
    <div style={{ position: "absolute", width: 46, height: 46, borderColor: C.yellow, borderStyle: "solid", borderWidth: 0, opacity: 0.85, ...style }} />
  );
  const mono: CSSProperties = { position: "absolute", fontFamily: MONO, fontSize: 17, letterSpacing: 3, color: C.text3 };
  return (
    <AbsoluteFill style={{ pointerEvents: "none" }}>
      {corner({ left: 36, top: 36, borderLeftWidth: 3, borderTopWidth: 3 })}
      {corner({ right: 36, top: 36, borderRightWidth: 3, borderTopWidth: 3 })}
      {corner({ left: 36, bottom: 36, borderLeftWidth: 3, borderBottomWidth: 3 })}
      {corner({ right: 36, bottom: 36, borderRightWidth: 3, borderBottomWidth: 3 })}
      <div style={{ ...mono, left: 100, top: 48 }}>
        <span style={{ color: C.cyan }}>◆</span> AEGIS://{label}
      </div>
      <div style={{ ...mono, right: 100, top: 48, color: C.yellow }}>{code}</div>
      <div style={{ ...mono, left: 100, bottom: 48 }}>LAN ONLY · PRIVAT · KI OPTIONAL</div>
      <div style={{ ...mono, right: 100, bottom: 48 }}>REC ● {frame % 30 < 18 ? "LIVE" : "    "}</div>
    </AbsoluteFill>
  );
}

/** Kicker, decoding headline and a sub line. */
export function Caption({
  kicker,
  title,
  sub,
  start = 0,
  style,
  size = 92,
}: {
  kicker: string;
  title: string[];
  sub?: string;
  start?: number;
  style?: CSSProperties;
  size?: number;
}) {
  const frame = useCurrentFrame();
  const t = frame - start;
  return (
    <div style={{ position: "absolute", display: "flex", flexDirection: "column", gap: 18, ...style }}>
      <div style={{ fontFamily: MONO, fontSize: 24, letterSpacing: 6, color: C.cyan, opacity: fade(t, 0, 6) }}>{kicker}</div>
      <div style={{ width: interpolate(t, [0, 14], [0, 120], clamp), height: 6, background: C.yellow, boxShadow: `0 0 18px ${C.yellow}` }} />
      {title.map((line, i) => (
        <DecodeText
          key={line}
          text={line}
          start={start + 4 + i * 8}
          style={{ fontFamily: DISPLAY, fontWeight: 700, fontSize: size, lineHeight: 1, letterSpacing: 2, color: C.text, textTransform: "uppercase", textShadow: `0 0 24px ${C.cyan}44` }}
        />
      ))}
      {sub && (
        <div
          style={{
            fontFamily: DISPLAY,
            fontWeight: 500,
            fontSize: 32,
            color: C.text2,
            maxWidth: 540,
            lineHeight: 1.35,
            opacity: fade(t, 18, 30),
            transform: `translateY(${interpolate(t, [18, 30], [16, 0], clamp)}px)`,
          }}
        >
          {sub}
        </div>
      )}
    </div>
  );
}

interface ClipProps {
  src: string;
  startFrom?: number;
  rate?: number;
}

export function Clip({ src, startFrom = 0, rate = 1 }: ClipProps) {
  const { fps } = useVideoConfig();
  return (
    <OffthreadVideo
      src={staticFile(src)}
      startFrom={Math.round(startFrom * fps)}
      playbackRate={rate}
      muted
      style={{ width: "100%", height: "100%", objectFit: "cover", display: "block" }}
    />
  );
}

/** A phone with a neon edge; the recording plays on its screen. */
export function Phone({ clip, height = 900, glow = C.cyan, children, style }: { clip?: ClipProps; height?: number; glow?: string; children?: ReactNode; style?: CSSProperties }) {
  const width = Math.round((height - 28) * (1082 / 2342)) + 28;
  return (
    <div
      style={{
        position: "absolute",
        width,
        height,
        borderRadius: 58,
        background: "#0b0d12",
        padding: 14,
        boxShadow: `0 0 0 2px ${glow}aa, 0 0 50px ${glow}55, 0 40px 80px rgba(0,0,0,.7)`,
        ...style,
      }}
    >
      <div style={{ position: "relative", width: "100%", height: "100%", borderRadius: 46, overflow: "hidden", background: C.void }}>
        {clip && <Clip {...clip} />}
        <div style={{ position: "absolute", top: 12, left: "50%", width: 18, height: 18, marginLeft: -9, borderRadius: 9, background: "#000" }} />
        {children}
      </div>
    </div>
  );
}

/** A browser window around a desktop recording. */
export function Browser({ clip, width = 1360, glow = C.yellow, style, children }: { clip?: ClipProps; width?: number; glow?: string; style?: CSSProperties; children?: ReactNode }) {
  const height = Math.round(width / 1.6) + 44;
  return (
    <div
      style={{
        position: "absolute",
        width,
        height,
        borderRadius: 14,
        overflow: "hidden",
        background: "#0b0d12",
        boxShadow: `0 0 0 2px ${glow}99, 0 0 60px ${glow}33, 0 50px 100px rgba(0,0,0,.75)`,
        ...style,
      }}
    >
      <div style={{ height: 44, display: "flex", alignItems: "center", gap: 10, padding: "0 18px", background: "#10131a", borderBottom: `1px solid ${C.line}` }}>
        {[C.red, C.yellow, C.cyan].map((color) => (
          <div key={color} style={{ width: 13, height: 13, borderRadius: 7, background: color, opacity: 0.85 }} />
        ))}
        <div style={{ marginLeft: 18, flex: 1, maxWidth: 520, height: 26, borderRadius: 6, background: C.void, border: `1px solid ${C.line}`, fontFamily: MONO, fontSize: 15, color: C.text3, display: "flex", alignItems: "center", padding: "0 12px", letterSpacing: 1 }}>
          <span style={{ color: C.cyan, marginRight: 8 }}>🔒</span>https://aegis.home
        </div>
      </div>
      <div style={{ position: "relative", width, height: height - 44 }}>
        {clip && <Clip {...clip} />}
        {children}
      </div>
    </div>
  );
}

/** Android heads-up notification with the real push copy. */
export function PushNotice({ start, end = 1e9, body }: { start: number; end?: number; body: string }) {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const t = frame - start;
  if (t < 0) return null;
  const drop = spring({ frame: t, fps, config: { damping: 14, stiffness: 120 } }) - spring({ frame: frame - end, fps, config: { damping: 20 } });
  return (
    <div
      style={{
        position: "absolute",
        left: 14,
        right: 14,
        top: interpolate(drop, [0, 1], [-160, 40]),
        borderRadius: 26,
        padding: "18px 20px",
        background: "rgba(22,26,34,.96)",
        border: `1.5px solid ${C.cyan}`,
        boxShadow: `0 0 30px ${C.cyan}66, 0 20px 40px rgba(0,0,0,.6)`,
        display: "flex",
        gap: 14,
        alignItems: "flex-start",
      }}
    >
      <Img src={staticFile("icon-512.png")} style={{ width: 46, height: 46, borderRadius: 12 }} />
      <div style={{ display: "flex", flexDirection: "column", gap: 4, flex: 1 }}>
        <div style={{ fontFamily: DISPLAY, fontSize: 15, color: C.text3, letterSpacing: 0.5 }}>Aegis · jetzt</div>
        <div style={{ fontFamily: DISPLAY, fontWeight: 700, fontSize: 20, color: C.text }}>Aegis</div>
        <div style={{ fontFamily: DISPLAY, fontWeight: 500, fontSize: 18, color: C.text2, lineHeight: 1.3 }}>{body}</div>
      </div>
    </div>
  );
}

/** A neon tap ripple, for taps added in the edit. */
export function Tap({ at, x, y }: { at: number; x: number; y: number }) {
  const frame = useCurrentFrame();
  const t = frame - at;
  if (t < 0 || t > 18) return null;
  const scale = interpolate(t, [0, 18], [0.4, 3]);
  return (
    <div
      style={{
        position: "absolute",
        left: x - 20,
        top: y - 20,
        width: 40,
        height: 40,
        borderRadius: 20,
        border: `4px solid ${C.cyan}`,
        boxShadow: `0 0 20px ${C.cyan}`,
        transform: `scale(${scale})`,
        opacity: 1 - t / 18,
      }}
    />
  );
}

// ------------------------------------------------------------------ v2: beat, camera, kinetic type, transitions

/** Kick drum envelope (1 on the beat, decaying) in the sections with drums; 120 BPM, so a beat every 15 frames. */
export function useBeat(offset = 0) {
  const frame = useCurrentFrame() + offset;
  const seconds = frame / 30;
  const groove = (seconds >= 8 && seconds < 24) || (seconds >= 28 && seconds < 48);
  const half = seconds >= 24 && seconds < 28;
  if (!groove && !half) return 0;
  const period = half ? 30 : 15;
  return Math.exp(-(frame % period) / 3.5);
}

/** Keyframed camera move: each key names a frame and the transform reached there, eased in between. */
export interface CameraKey {
  f: number;
  scale?: number;
  x?: number;
  y?: number;
  rotate?: number;
  rotateY?: number;
}
export function Camera({ keys, children, origin = "50% 50%" }: { keys: CameraKey[]; children: ReactNode; origin?: string }) {
  const frame = useCurrentFrame();
  const value = (prop: keyof Omit<CameraKey, "f">, fallback: number) => {
    const frames = keys.map((k) => k.f);
    const values = keys.map((k) => k[prop] ?? fallback);
    if (keys.length === 1) return values[0];
    return interpolate(frame, frames, values, { ...clamp, easing: (t) => (t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2) });
  };
  return (
    <AbsoluteFill
      style={{
        transformOrigin: origin,
        transform: `perspective(1800px) translate(${value("x", 0)}px, ${value("y", 0)}px) scale(${value("scale", 1)}) rotate(${value("rotate", 0)}deg) rotateY(${value("rotateY", 0)}deg)`,
      }}
    >
      {children}
    </AbsoluteFill>
  );
}

export type EnterKind = "stripes" | "whip" | "zoom" | "slice";

/** How a shot comes in on its cut: stripes, a whip pan, a zoom through or vertical slices. */
export function Enter({ kind, children, length = 9 }: { kind: EnterKind; children: ReactNode; length?: number }) {
  const frame = useCurrentFrame();
  const p = interpolate(frame, [0, length], [0, 1], { ...clamp, easing: (t) => 1 - Math.pow(1 - t, 3) });
  if (p >= 1) return <AbsoluteFill>{children}</AbsoluteFill>;
  let style: CSSProperties = {};
  if (kind === "stripes") {
    const band = 36;
    const open = p * band;
    const mask = `repeating-linear-gradient(0deg, black 0px, black ${open}px, transparent ${open}px, transparent ${band}px)`;
    style = { maskImage: mask, WebkitMaskImage: mask, transform: `translateX(${(1 - p) * 60}px)` };
  } else if (kind === "slice") {
    const band = 60;
    const open = p * band;
    const mask = `repeating-linear-gradient(90deg, black 0px, black ${open}px, transparent ${open}px, transparent ${band}px)`;
    style = { maskImage: mask, WebkitMaskImage: mask, transform: `translateY(${(1 - p) * -40}px)` };
  } else if (kind === "whip") {
    style = { transform: `translateX(${(1 - p) * 1400}px) skewX(${(1 - p) * -18}deg)`, filter: `blur(${(1 - p) * 18}px)` };
  } else {
    style = { transform: `scale(${1 + (1 - p) * 0.5})`, filter: `blur(${(1 - p) * 14}px) brightness(${1 + (1 - p) * 1.5})`, opacity: p };
  }
  return <AbsoluteFill style={style}>{children}</AbsoluteFill>;
}

/** Letters slam in one after another, overshooting from large and blurred. */
export function SlamText({ text, start = 0, stagger = 1.6, style }: { text: string; start?: number; stagger?: number; style?: CSSProperties }) {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  return (
    <span style={{ display: "inline-flex", whiteSpace: "pre", ...style }}>
      {[...text].map((ch, i) => {
        const t = frame - start - i * stagger;
        const s = spring({ frame: t, fps, config: { damping: 11, stiffness: 220 } });
        return (
          <span
            key={i}
            style={{
              display: "inline-block",
              opacity: t < 0 ? 0 : Math.min(1, s * 1.5),
              transform: `scale(${interpolate(s, [0, 1], [2.6, 1])}) translateY(${interpolate(s, [0, 1], [-30, 0])}px)`,
              filter: `blur(${interpolate(s, [0, 1], [10, 0], clamp)}px)`,
            }}
          >
            {ch}
          </span>
        );
      })}
    </span>
  );
}

/** A label that points at something on screen: a dot, a line drawing out, then a decoding chip. */
export function Callout({
  at,
  x,
  y,
  dx = 160,
  dy = -70,
  text,
  color = C.yellow,
}: {
  at: number;
  x: number;
  y: number;
  dx?: number;
  dy?: number;
  text: string;
  color?: string;
}) {
  const frame = useCurrentFrame();
  const t = frame - at;
  if (t < 0) return null;
  const line = interpolate(t, [0, 7], [0, 1], clamp);
  const len = Math.hypot(dx, dy);
  const angle = (Math.atan2(dy, dx) * 180) / Math.PI;
  return (
    <div style={{ position: "absolute", left: x, top: y, pointerEvents: "none" }}>
      <div style={{ position: "absolute", left: -9, top: -9, width: 18, height: 18, borderRadius: 9, border: `3px solid ${color}`, boxShadow: `0 0 14px ${color}`, transform: `scale(${interpolate(t, [0, 5], [2.5, 1], clamp)})` }} />
      <div style={{ position: "absolute", left: 0, top: -1.5, width: len * line, height: 3, background: color, boxShadow: `0 0 10px ${color}`, transformOrigin: "0 50%", transform: `rotate(${angle}deg)` }} />
      {t > 6 && (
        <div
          style={{
            position: "absolute",
            left: dx + (dx >= 0 ? 0 : -10),
            top: dy - 22,
            transform: dx >= 0 ? undefined : "translateX(-100%)",
            background: `${C.void}ee`,
            border: `2px solid ${color}`,
            boxShadow: `0 0 20px ${color}66`,
            padding: "8px 16px",
            fontFamily: MONO,
            fontSize: 24,
            letterSpacing: 3,
            color,
            whiteSpace: "nowrap",
            clipPath: "polygon(0 0, calc(100% - 12px) 0, 100% 12px, 100% 100%, 12px 100%, 0 calc(100% - 12px))",
          }}
        >
          <DecodeText text={text} start={at + 6} speed={0.5} split={false} />
        </div>
      )}
    </div>
  );
}

/** Rotating dashed rings, a targeting reticle behind a device or logo. */
export function Rings({ x, y, size = 900, color = C.cyan, opacity = 0.5 }: { x: number; y: number; size?: number; color?: string; opacity?: number }) {
  const frame = useCurrentFrame();
  const beat = useBeat();
  const ring = (r: number, dash: string, speed: number, width: number) => (
    <circle cx={size / 2} cy={size / 2} r={r} fill="none" stroke={color} strokeWidth={width} strokeDasharray={dash} transform={`rotate(${frame * speed} ${size / 2} ${size / 2})`} />
  );
  return (
    <svg width={size} height={size} style={{ position: "absolute", left: x - size / 2, top: y - size / 2, opacity: opacity * (0.75 + 0.25 * beat), transform: `scale(${1 + beat * 0.03})` }}>
      {ring(size * 0.48, "4 14", 0.4, 2)}
      {ring(size * 0.42, "120 40 10 40", -0.7, 3)}
      {ring(size * 0.35, "2 8", 1.2, 6)}
      {ring(size * 0.3, "300 600", -1.8, 2)}
    </svg>
  );
}

/** A bright diagonal sweep over a device screen. */
export function Sweep({ at, length = 18 }: { at: number; length?: number }) {
  const frame = useCurrentFrame();
  const t = frame - at;
  if (t < 0 || t > length) return null;
  const pos = interpolate(t, [0, length], [-60, 160]);
  return (
    <AbsoluteFill
      style={{
        pointerEvents: "none",
        background: `linear-gradient(115deg, transparent ${pos - 20}%, rgba(255,255,255,.22) ${pos}%, transparent ${pos + 12}%)`,
        mixBlendMode: "screen",
      }}
    />
  );
}

/** Hex codes drifting upwards in the background. */
export function DataRain({ count = 26, color = C.cyan }: { count?: number; color?: string }) {
  const frame = useCurrentFrame();
  return (
    <AbsoluteFill style={{ pointerEvents: "none" }}>
      {Array.from({ length: count }, (_, i) => {
        const x = random(`dx${i}`) * 1920;
        const speed = 1 + random(`ds${i}`) * 3;
        const y = 1100 - ((frame * speed + random(`dy${i}`) * 1200) % 1300);
        const code = Math.floor(random(`dc${i}${Math.floor(frame / 6)}`) * 0xffffff).toString(16).toUpperCase().padStart(6, "0");
        return (
          <div key={i} style={{ position: "absolute", left: x, top: y, fontFamily: MONO, fontSize: 16, color, opacity: 0.18 + random(`do${i}`) * 0.2, letterSpacing: 2 }}>
            0x{code}
          </div>
        );
      })}
    </AbsoluteFill>
  );
}

/** A small feature label that sits in a corner of a montage shot. */
export function Tag({ text, start = 0, color = C.yellow }: { text: string; start?: number; color?: string }) {
  const frame = useCurrentFrame();
  const t = frame - start;
  const w = interpolate(t, [0, 6], [0, 1], { ...clamp, easing: (v) => 1 - Math.pow(1 - v, 3) });
  return (
    <div style={{ position: "absolute", left: 120, bottom: 120, display: "flex", alignItems: "center", gap: 18 }}>
      <div style={{ width: 14, height: 70, background: color, boxShadow: `0 0 20px ${color}`, transform: `scaleY(${w})` }} />
      <div style={{ overflow: "hidden", width: `${w * 100}%` }}>
        <SlamText text={text} start={start + 2} stagger={0.8} style={{ fontFamily: DISPLAY, fontWeight: 700, fontSize: 76, color: C.text, letterSpacing: 4, textShadow: `0 0 30px ${C.void}` }} />
      </div>
    </div>
  );
}
