// v3: the long, calm cut. Same look as the promo, but every feature gets time, scenes crossfade instead of
// glitching, and the camera drifts instead of punching. Scene changes sit on bar lines of the calm soundtrack.
import type { JSX, ReactNode } from "react";
import { AbsoluteFill, Audio, Img, interpolate, Sequence, staticFile, useCurrentFrame } from "remotion";
import {
  BeatContext,
  Background,
  Browser,
  C,
  Callout,
  Camera,
  Caption,
  Clip,
  DataRain,
  DecodeText,
  DISPLAY,
  fade,
  Hud,
  MONO,
  Overlay,
  Phone,
  PushNotice,
  Rings,
  Sweep,
  Tap,
  useFonts,
} from "./fx";
import type { Segment } from "./timeline";

const BAR = 3.2; // 75 BPM, four beats
const BARS = 37;
export const CALM_FRAMES = Math.round(BARS * BAR * 30);
const s = (seconds: number) => Math.round(seconds * 30);
/** Each scene starts this much before its bar line and fades in over the previous one. */
const LEAD = 0.6;

/** Soft kick on beats one and three while the drums play (bars 4-33), a slow breath otherwise. */
const calmBeat = (frame: number) => {
  const seconds = frame / 30;
  const breath = 0.25 + 0.25 * Math.sin((2 * Math.PI * seconds) / (2 * BAR));
  if (seconds < 4 * BAR || seconds >= 34 * BAR) return breath;
  return Math.max(breath, 0.55 * Math.exp(-(frame % 48) / 12));
};

/** Plays parts of one recording back to back; the last part keeps playing until the scene ends. */
function Segments({ src, segments }: { src: string; segments: Segment[] }) {
  let at = 0;
  return (
    <>
      {segments.map((segment, i) => {
        const from = at;
        const length = s((segment.to - segment.from) / segment.rate);
        at += length;
        return (
          <Sequence key={segment.from} from={from} durationInFrames={i === segments.length - 1 ? undefined : length}>
            <Clip src={src} startFrom={segment.from} rate={segment.rate} />
          </Sequence>
        );
      })}
    </>
  );
}

/** Lines that fade up one after another. */
function Lines({ lines, at, size = 92, color = C.text, gap = 0.8 }: { lines: string[]; at: number; size?: number; color?: string; gap?: number }) {
  const frame = useCurrentFrame();
  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 8 }}>
      {lines.map((line, i) => {
        const start = s(at + i * gap);
        return (
          <div
            key={line}
            style={{
              fontFamily: DISPLAY,
              fontWeight: 700,
              fontSize: size,
              lineHeight: 1.05,
              letterSpacing: 4,
              color: i === lines.length - 1 ? C.yellow : color,
              opacity: fade(frame, start, start + 20),
              transform: `translateY(${interpolate(frame, [start, start + 24], [24, 0], { extrapolateLeft: "clamp", extrapolateRight: "clamp" })}px)`,
              filter: `blur(${interpolate(frame, [start, start + 16], [6, 0], { extrapolateLeft: "clamp", extrapolateRight: "clamp" })}px)`,
              textShadow: `0 0 30px ${C.void}`,
            }}
          >
            {line}
          </div>
        );
      })}
    </div>
  );
}

/** Shows a caption from one moment to another, fading out at the end. */
function Timed({ from, to, children }: { from: number; to: number; children: ReactNode }) {
  const frame = useCurrentFrame();
  if (frame < s(from) || frame > s(to)) return null;
  return <div style={{ opacity: fade(frame, s(to) - 15, s(to), 1, 0) }}>{children}</div>;
}

// ------------------------------------------------------------------ scenes

function Intro() {
  const frame = useCurrentFrame();
  const words = ["BRIEFE.", "RECHNUNGEN.", "VERTRÄGE.", "FRISTEN."];
  const logo = s(9.6);
  const wordsOut = fade(frame, s(6.6), s(7.6), 1, 0);
  const iconIn = fade(frame, s(7.4), logo);
  return (
    <AbsoluteFill>
      <Background intensity={fade(frame, 0, s(3)) * 0.8} hue={C.cyan} />
      <DataRain count={14} />
      <AbsoluteFill style={{ alignItems: "center", justifyContent: "center", opacity: wordsOut }}>
        <div style={{ display: "flex", flexWrap: "wrap", justifyContent: "center", columnGap: 40, rowGap: 10, maxWidth: 1300 }}>
          {words.map((word, i) => {
            const start = s(0.8 + i * 1.6);
            return (
              <div key={word} style={{ fontFamily: DISPLAY, fontWeight: 700, fontSize: 96, letterSpacing: 6, color: i === 3 ? C.yellow : C.text, opacity: fade(frame, start, start + 24), filter: `blur(${interpolate(frame, [start, start + 24], [10, 0], { extrapolateLeft: "clamp", extrapolateRight: "clamp" })}px)` }}>
                {word}
              </div>
            );
          })}
        </div>
      </AbsoluteFill>
      <Rings x={960} y={400} size={interpolate(frame, [s(7.4), s(13.4)], [1300, 700], { extrapolateLeft: "clamp", extrapolateRight: "clamp" })} opacity={iconIn * 0.6} />
      <AbsoluteFill style={{ alignItems: "center", justifyContent: "center", flexDirection: "column", gap: 24, paddingBottom: 60 }}>
        <Img src={staticFile("aegis-icon.png")} style={{ width: 300, height: 300, opacity: iconIn, transform: `scale(${interpolate(frame, [s(7.4), s(13.4)], [0.85, 1.02], { extrapolateRight: "clamp" })})`, filter: `drop-shadow(0 0 ${30 * iconIn}px ${C.cyan}aa)` }} />
        <div style={{ fontFamily: DISPLAY, fontWeight: 700, fontSize: 170, letterSpacing: 30, color: C.text, opacity: fade(frame, logo, logo + 24), textShadow: `0 0 40px ${C.cyan}88` }}>AEGIS</div>
        {frame >= logo + 18 && <DecodeText text="DER HAUSHALT, DER MITDENKT." start={logo + 18} speed={0.45} style={{ fontFamily: MONO, fontSize: 38, letterSpacing: 9, color: C.yellow }} />}
      </AbsoluteFill>
    </AbsoluteFill>
  );
}

const UPLOAD: Segment[] = [
  { from: 0, to: 6.6, rate: 1 },
  { from: 8.0, to: 18.8, rate: 2 },
  { from: 19.3, to: 25.3, rate: 1 },
];

function Upload() {
  const frame = useCurrentFrame();
  const second = UPLOAD[0].to - UPLOAD[0].from;
  const third = second + (UPLOAD[1].to - UPLOAD[1].from) / UPLOAD[1].rate;
  return (
    <AbsoluteFill>
      <Background />
      <DataRain count={14} />
      <Rings x={1450} y={540} size={1000} opacity={0.25} />
      <Camera keys={[{ f: 0, scale: 1, rotateY: -6 }, { f: s(8), scale: 1.08, rotateY: 0 }, { f: s(16.6), scale: 1.04, rotateY: 4 }]} origin="76% 45%">
        <Phone height={940} style={{ right: 290, top: 70 }}>
          <Segments src="phone-upload.mp4" segments={UPLOAD} />
          <Sweep at={s(second)} length={30} />
          <Sweep at={s(third)} length={30} />
        </Phone>
      </Camera>
      <Timed from={0.3} to={second}>
        <Caption kicker="// 01 ERFASSEN" title={["FOTO.", "FERTIG."]} sub="Seite fotografieren. Aegis findet die Kanten und begradigt das Blatt." start={s(0.6)} style={{ left: 170, top: 300 }} />
      </Timed>
      <Timed from={second} to={third}>
        <Caption kicker="// 02 ARCHIV" title={["SICHER", "GESPEICHERT."]} sub="Erst dauerhaft gespeichert, dann archiviert und per OCR durchsuchbar." start={s(second) + 4} style={{ left: 170, top: 300 }} />
      </Timed>
      {frame >= s(third) && (
        <div style={{ position: "absolute", left: 160, top: 300 }}>
          <div style={{ fontFamily: MONO, fontSize: 24, letterSpacing: 6, color: C.cyan, opacity: fade(frame, s(third), s(third) + 10) }}>// 03 KI-EINORDNUNG</div>
          <div style={{ marginTop: 22, display: "flex", flexDirection: "column", gap: 16 }}>
            {[
              ["TITEL", "Beitragsanpassung Kfz 2027"],
              ["ORDNER", "Versicherungen / KFZ"],
              ["LABELS", "Brief · Vulkan · KFZ"],
              ["DATUM", "01.10.2026"],
            ].map(([k, v], i) => {
              const at = s(third + 0.4 + i * 0.6);
              return (
                <div key={k} style={{ display: "flex", gap: 20, alignItems: "baseline", opacity: fade(frame, at, at + 14), transform: `translateX(${interpolate(frame, [at, at + 18], [-30, 0], { extrapolateLeft: "clamp", extrapolateRight: "clamp" })}px)` }}>
                  <span style={{ fontFamily: MONO, fontSize: 26, color: C.yellow, width: 130, letterSpacing: 4 }}>{k}</span>
                  <span style={{ fontFamily: DISPLAY, fontWeight: 600, fontSize: 40, color: C.text }}>{v}</span>
                  <span style={{ fontFamily: MONO, fontSize: 30, color: C.cyan, opacity: fade(frame, at + 12, at + 20) }}>✓</span>
                </div>
              );
            })}
          </div>
          <div style={{ marginTop: 30, fontFamily: DISPLAY, fontWeight: 500, fontSize: 30, color: C.text2, maxWidth: 620, opacity: fade(frame, s(third + 3), s(third + 3.6)) }}>
            Titel, Ordner und Labels schlägt die KI vor. Bestätigte Angaben überschreibt sie nie.
          </div>
        </div>
      )}
      <Timed from={2.4} to={second}>
        <Callout at={s(2.4)} x={1250} y={330} dx={-200} dy={-110} text="KANTEN ERKANNT" color={C.cyan} />
      </Timed>
      <Timed from={second + 2} to={third}>
        <Callout at={s(second + 2)} x={1300} y={300} dx={-230} dy={-80} text="OCR ✓" />
      </Timed>
    </AbsoluteFill>
  );
}

function Pages() {
  const frame = useCurrentFrame();
  return (
    <AbsoluteFill>
      <Background hue={C.cyan} />
      <DataRain count={14} />
      <Rings x={560} y={540} size={950} opacity={0.25} color={C.yellow} />
      <Camera keys={[{ f: 0, scale: 1.02, rotateY: 8 }, { f: s(13.4), scale: 1.1, rotateY: -4 }]} origin="30% 50%">
        <Phone height={940} style={{ left: 330, top: 70 }} clip={{ src: "phone-pages.mp4", startFrom: 0.2, rate: 1 }} />
      </Camera>
      <div style={{ position: "absolute", left: 960, top: 280 }}>
        <div style={{ fontFamily: MONO, fontSize: 24, letterSpacing: 6, color: C.cyan, marginBottom: 20, opacity: fade(frame, 10, 24) }}>// 04 SEITENEDITOR</div>
        <Lines lines={["TRENNEN.", "DREHEN.", "BENENNEN."]} at={1.4} gap={3.6} size={104} />
        <div style={{ fontFamily: DISPLAY, fontWeight: 500, fontSize: 32, color: C.text2, maxWidth: 680, marginTop: 24, lineHeight: 1.35, opacity: fade(frame, s(4), s(4.8)) }}>
          Ein Briefkasten-Scan mit zwei Briefen wird zu zwei sauberen Dokumenten. Das Original bleibt erhalten.
        </div>
      </div>
    </AbsoluteFill>
  );
}

function Today() {
  const frame = useCurrentFrame();
  const detail = 4.6;
  return (
    <AbsoluteFill>
      <Background hue={C.yellow} />
      <Camera keys={[{ f: 0, scale: 1, rotateY: -8 }, { f: s(detail), scale: 1.02, rotateY: -2 }, { f: s(detail + 1.6), scale: 1.42, x: -330, y: 80 }, { f: s(10.2), scale: 1.48, x: -350, y: 84 }]} origin="70% 30%">
        <Browser clip={{ src: "desk-today.mp4", startFrom: 0, rate: 1 }} width={1240} style={{ right: 80, top: 150 }} />
      </Camera>
      <Timed from={0.4} to={detail + 0.4}>
        <Caption kicker="// 05 HEUTE" title={["ALLES", "IM BLICK."]} sub="Überfälliges, Geplantes und die nächsten Kündigungstermine auf einer Seite." start={s(0.7)} size={80} style={{ left: 110, top: 300 }} />
      </Timed>
      <Timed from={detail + 0.4} to={10.4}>
        <div style={{ position: "absolute", left: 90, bottom: 120, padding: "30px 40px 26px 30px", background: "rgba(7,8,11,0.9)", border: `1px solid ${C.yellow}55`, display: "flex", alignItems: "flex-end", gap: 28, opacity: fade(frame, s(detail + 1.4), s(detail + 2.2)) }}>
          <div style={{ fontFamily: DISPLAY, fontWeight: 700, fontSize: 260, lineHeight: 0.8, color: C.yellow, textShadow: `0 0 60px ${C.yellow}66` }}>6</div>
          <div style={{ display: "flex", flexDirection: "column", gap: 8, paddingBottom: 20 }}>
            <div style={{ fontFamily: MONO, fontSize: 32, letterSpacing: 8, color: C.text }}>TAGE BIS ZUR</div>
            <div style={{ fontFamily: MONO, fontSize: 32, letterSpacing: 8, color: C.text }}>KÜNDIGUNGSFRIST</div>
            <div style={{ fontFamily: DISPLAY, fontWeight: 500, fontSize: 26, color: C.text2, marginTop: 8 }}>berechnet aus bestätigten Angaben, mit Beleg</div>
          </div>
        </div>
      </Timed>
    </AbsoluteFill>
  );
}

function Notify() {
  const frame = useCurrentFrame();
  const push = s(LEAD + 1.0);
  const tap = s(LEAD + 2.9);
  return (
    <AbsoluteFill>
      <Background hue={C.cyan} />
      <DataRain count={14} />
      <Rings x={1300} y={480} size={1000} opacity={0.25} />
      <Camera keys={[{ f: 0, scale: 1 }, { f: push, scale: 1 }, { f: push + 30, scale: 1.3, y: 230 }, { f: tap + 6, scale: 1.3, y: 230 }, { f: tap + 40, scale: 1.04, y: 0 }, { f: s(13.4), scale: 1.08, y: -10 }]} origin="68% 10%">
        <Phone height={940} clip={{ src: "phone-today.mp4", startFrom: 0, rate: 0.92 }} style={{ left: 1080, top: 70 }}>
          <Sequence from={s(LEAD)}>
            <PushNotice start={s(1.0)} end={s(3.0)} body="Kündigung für „Iron Grid Fitness“ bis 09.10.2026 prüfen." />
            <Tap at={s(2.9)} x={200} y={110} />
          </Sequence>
        </Phone>
      </Camera>
      <Timed from={0.3} to={6.6}>
        <Caption kicker="// 06 ERINNERN" title={["RECHTZEITIG", "ERINNERT."]} sub="Eine Push-Nachricht aufs Handy, bevor eine Frist abläuft. Antippen öffnet die Aufgabe." start={s(0.6)} size={84} style={{ left: 150, top: 300 }} />
      </Timed>
      <Timed from={6.6} to={13.4}>
        <Caption kicker="// 07 AUFGABEN" title={["FRIST BLEIBT", "FRIST."]} sub="Geplante Arbeit und Erinnerungen verschieben nie den echten Termin." start={s(6.7)} size={84} style={{ left: 150, top: 300 }} />
      </Timed>
    </AbsoluteFill>
  );
}

function Chat() {
  const frame = useCurrentFrame();
  const rate = 1.18;
  const answer = s(LEAD + (13 - 0.3) / rate);
  return (
    <AbsoluteFill>
      <Background />
      <DataRain count={14} />
      <Camera keys={[{ f: 0, scale: 0.96, rotateY: 8 }, { f: s(4), scale: 1, rotateY: 0 }, { f: answer, scale: 1.02 }, { f: answer + 45, scale: 1.45, x: -80 }, { f: s(20), scale: 1.5, x: -86 }]} origin="75% 52%">
        <Browser clip={{ src: "desk-chat.mp4", startFrom: 0.3, rate }} width={1300} glow={C.cyan} style={{ left: 520, top: 150 }}>
          <Sweep at={answer} length={30} />
        </Browser>
      </Camera>
      <Callout at={answer + 50} x={450} y={566} dx={-60} dy={-150} text="QUELLE · S. 2 ✓" color={C.cyan} />
      <Callout at={answer + 70} x={450} y={652} dx={-60} dy={150} text="QUELLE · S. 1 ✓" color={C.yellow} />
      <div style={{ opacity: 1 - fade(frame, answer - 12, answer + 6) }}>
        <Caption kicker="// 08 KI-ASSISTENT" title={["FRAG", "AEGIS."]} sub="Fragen in eigenen Worten. Die Antwort kommt aus euren Unterlagen, mit Dokument und Seite." start={s(0.8)} size={92} style={{ left: 110, top: 300 }} />
      </div>
    </AbsoluteFill>
  );
}

function ChatPhone() {
  return (
    <AbsoluteFill>
      <Background hue={C.cyan} />
      <Rings x={1300} y={540} size={1000} opacity={0.3} />
      <Camera keys={[{ f: 0, scale: 1, rotate: -1.5 }, { f: s(7), scale: 1.05, rotate: 0 }]} origin="68% 50%">
        <Phone height={960} clip={{ src: "phone-chat.mp4", startFrom: 1.5, rate: 1.15 }} style={{ left: 1075, top: 60 }} />
      </Camera>
      <div style={{ position: "absolute", left: 150, top: 330 }}>
        <Lines lines={["AUCH", "UNTERWEGS."]} at={0.8} gap={0.9} size={110} />
        <div style={{ fontFamily: DISPLAY, fontWeight: 500, fontSize: 32, color: C.text2, maxWidth: 600, marginTop: 24, lineHeight: 1.35 }}>
          <DelayedFade at={2.6}>Die gleiche Frage, die gleichen Quellen, auf dem Handy.</DelayedFade>
        </div>
      </div>
    </AbsoluteFill>
  );
}

function DelayedFade({ at, children }: { at: number; children: ReactNode }) {
  const frame = useCurrentFrame();
  return <span style={{ opacity: fade(frame, s(at), s(at) + 18) }}>{children}</span>;
}

/** Two features in one scene: search, then a soft dissolve into the theme switch. */
function SearchThemes() {
  const frame = useCurrentFrame();
  const swap = s(LEAD + 3.6);
  const themeIn = fade(frame, swap - 9, swap + 9);
  return (
    <AbsoluteFill>
      <Background hue={C.yellow} intensity={0.8} />
      <Camera keys={[{ f: 0, scale: 1.0 }, { f: s(10.2), scale: 1.08 }]} origin="55% 40%">
        <Browser width={1220} style={{ left: 640, top: 190 }}>
          <AbsoluteFill>
            <Clip src="desk-search.mp4" startFrom={0} rate={0.94} />
          </AbsoluteFill>
          {frame >= swap - 9 && (
            <AbsoluteFill style={{ opacity: themeIn }}>
              <Sequence from={swap - 9}>
                <Clip src="desk-theme.mp4" startFrom={0.1} rate={0.9} />
              </Sequence>
            </AbsoluteFill>
          )}
        </Browser>
      </Camera>
      <div style={{ opacity: 1 - themeIn }}>
        <Caption kicker="// 09 SUCHE" title={["VOLLTEXT."]} sub="Findet Wörter im Text gescannter Briefe, nicht nur im Titel." start={s(0.7)} size={80} style={{ left: 110, top: 330 }} />
      </div>
      {frame >= swap && (
        <Caption kicker="// 10 DARSTELLUNG" title={["3 FARB-", "THEMEN."]} sub="Hell, dunkel oder Neon. Jede Person wählt selbst." start={swap + 4} size={80} style={{ left: 110, top: 330 }} />
      )}
    </AbsoluteFill>
  );
}

function Household() {
  const frame = useCurrentFrame();
  return (
    <AbsoluteFill>
      <Background hue={C.cyan} />
      <DataRain count={14} />
      <Camera keys={[{ f: 0, scale: 1.0 }, { f: s(10.2), scale: 1.05 }]} origin="40% 50%">
        <Browser clip={{ src: "desk-documents.mp4", startFrom: 0.2, rate: 1.04 }} width={1180} glow={C.yellow} style={{ left: 120, top: 200, transform: "perspective(1600px) rotateY(6deg)" }} />
        <Phone height={860} clip={{ src: "phone-upload.mp4", startFrom: 19.3, rate: 0.5 }} style={{ left: 1390, top: 150 }} />
      </Camera>
      <div style={{ position: "absolute", left: 140, top: 70, display: "flex", flexDirection: "column", gap: 10, opacity: fade(frame, s(1.0), s(1.6)) }}>
        <div style={{ fontFamily: DISPLAY, fontWeight: 700, fontSize: 56, color: C.text, letterSpacing: 4 }}>ORDNER, LABELS, VORSCHAU.</div>
        {frame >= s(2.4) && <DecodeText text="HANDY & DESKTOP, FÜR BEIDE IM HAUSHALT." start={s(2.4)} speed={0.5} split={false} style={{ fontFamily: MONO, fontSize: 26, color: C.cyan, letterSpacing: 4 }} />}
      </div>
    </AbsoluteFill>
  );
}

function EndCard() {
  const frame = useCurrentFrame();
  const out = fade(frame, s(8.4), s(10.2), 1, 0);
  const chips = ["DOKUMENTE", "SEITENEDITOR", "VOLLTEXTSUCHE", "VERTRÄGE & FRISTEN", "AUFGABEN", "PUSH", "KI-ASSISTENT", "3 THEMEN"];
  const iconIn = fade(frame, s(0.6), s(2.0));
  return (
    <AbsoluteFill style={{ opacity: out }}>
      <Background hue={C.cyan} />
      <Rings x={960} y={300} size={interpolate(frame, [0, s(10)], [560, 700])} opacity={0.4 * iconIn} />
      <AbsoluteFill style={{ alignItems: "center", justifyContent: "center", flexDirection: "column", gap: 26 }}>
        <Img src={staticFile("aegis-icon.png")} style={{ width: 260, height: 260, opacity: iconIn, transform: `scale(${interpolate(frame, [s(0.6), s(10)], [0.92, 1.02])})`, filter: `drop-shadow(0 0 30px ${C.cyan}88)` }} />
        <div style={{ fontFamily: DISPLAY, fontWeight: 700, fontSize: 160, letterSpacing: 28, color: C.text, opacity: fade(frame, s(1.2), s(2.2)), textShadow: `0 0 40px ${C.cyan}88` }}>AEGIS</div>
        {frame >= s(2.2) && <DecodeText text="EUER HAUSHALT. UNTER KONTROLLE." start={s(2.2)} speed={0.45} style={{ fontFamily: MONO, fontSize: 38, letterSpacing: 8, color: C.yellow }} />}
        <div style={{ display: "flex", flexWrap: "wrap", justifyContent: "center", gap: 16, marginTop: 14, maxWidth: 1300 }}>
          {chips.map((item, i) => {
            const at = s(3.6 + i * 0.25);
            return (
              <div
                key={item}
                style={{
                  fontFamily: MONO,
                  fontSize: 22,
                  letterSpacing: 4,
                  color: C.cyan,
                  border: `2px solid ${C.cyan}88`,
                  padding: "10px 18px",
                  opacity: fade(frame, at, at + 15),
                  transform: `translateY(${interpolate(frame, [at, at + 20], [18, 0], { extrapolateLeft: "clamp", extrapolateRight: "clamp" })}px)`,
                  clipPath: "polygon(0 0, calc(100% - 14px) 0, 100% 14px, 100% 100%, 14px 100%, 0 calc(100% - 14px))",
                }}
              >
                {item}
              </div>
            );
          })}
        </div>
      </AbsoluteFill>
    </AbsoluteFill>
  );
}

// Scene boundaries in bars of the soundtrack.
const SCENES: { from: number; to: number; label: string; Component: () => JSX.Element }[] = [
  { from: 0, to: 4, label: "BOOT", Component: Intro },
  { from: 4, to: 9, label: "UPLOAD", Component: Upload },
  { from: 9, to: 13, label: "SEITEN", Component: Pages },
  { from: 13, to: 16, label: "HEUTE", Component: Today },
  { from: 16, to: 20, label: "PUSH", Component: Notify },
  { from: 20, to: 26, label: "ASSISTENT", Component: Chat },
  { from: 26, to: 28, label: "MOBIL", Component: ChatPhone },
  { from: 28, to: 31, label: "SUCHE", Component: SearchThemes },
  { from: 31, to: 34, label: "HAUSHALT", Component: Household },
  { from: 34, to: BARS, label: "END", Component: EndCard },
];

/** Fades a scene in over the previous one with a slight blur and settle. */
function Dissolve({ children }: { children: ReactNode }) {
  const frame = useCurrentFrame();
  const p = interpolate(frame, [0, s(LEAD)], [0, 1], { extrapolateLeft: "clamp", extrapolateRight: "clamp", easing: (t) => t * t * (3 - 2 * t) });
  return <AbsoluteFill style={{ opacity: p, filter: p < 1 ? `blur(${(1 - p) * 10}px)` : undefined, transform: `scale(${1.03 - 0.03 * p})` }}>{children}</AbsoluteFill>;
}

export function Calm() {
  useFonts();
  const frame = useCurrentFrame();
  const current = SCENES.find((scene) => frame >= s(scene.from * BAR) && frame < s(scene.to * BAR)) ?? SCENES[0];
  return (
    <BeatContext.Provider value={calmBeat}>
      <AbsoluteFill style={{ background: C.void }}>
        <Audio src={staticFile("soundtrack-calm.wav")} />
        {SCENES.map(({ from, to, Component, label }, i) => {
          const start = i === 0 ? 0 : s(from * BAR - LEAD);
          return (
            <Sequence key={label} from={start} durationInFrames={s(to * BAR) - start}>
              {i === 0 ? <Component /> : <Dissolve><Component /></Dissolve>}
            </Sequence>
          );
        })}
        {current.label !== "END" && current.label !== "BOOT" && <Hud label={current.label} />}
        <Overlay />
      </AbsoluteFill>
    </BeatContext.Provider>
  );
}
