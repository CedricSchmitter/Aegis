import type { JSX, ReactNode } from "react";
import { AbsoluteFill, Audio, Img, interpolate, Sequence, spring, staticFile, useCurrentFrame, useVideoConfig } from "remotion";
import {
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
  Enter,
  fade,
  GlitchBurst,
  Hud,
  MONO,
  Overlay,
  Phone,
  PushNotice,
  Rings,
  Shake,
  SlamText,
  Sweep,
  Tag,
  Tap,
  useBeat,
  useFonts,
  type EnterKind,
} from "./fx";
import { CUTS, PROMO_SECONDS, segmentsLength, type Segment } from "./timeline";

export const PROMO_FRAMES = PROMO_SECONDS * 30;
const s = (seconds: number) => Math.round(seconds * 30);

/** Plays parts of one recording back to back, each at its own speed. */
function Segments({ src, segments }: { src: string; segments: Segment[] }) {
  let at = 0;
  return (
    <>
      {segments.map((segment) => {
        const from = at;
        const length = s((segment.to - segment.from) / segment.rate);
        at += length;
        return (
          <Sequence key={segment.from} from={from} durationInFrames={length}>
            <Clip src={src} startFrom={segment.from} rate={segment.rate} />
          </Sequence>
        );
      })}
    </>
  );
}

/** Everything in the shot bumps slightly on each kick. */
function Bump({ children, amount = 0.012 }: { children: ReactNode; amount?: number }) {
  const beat = useBeat();
  return <AbsoluteFill style={{ transform: `scale(${1 + beat * amount})` }}>{children}</AbsoluteFill>;
}

// ------------------------------------------------------------------ 0-8 s

const WORDS = ["BRIEFE.", "RECHNUNGEN.", "VERTRÄGE.", "FRISTEN.", "POLICEN.", "MAHNUNGEN.", "BESCHEIDE.", "CHAOS?"];

/** A word on every beat, then the shield assembles inside its reticle while the riser builds. */
function Intro() {
  const frame = useCurrentFrame();
  const index = Math.min(WORDS.length - 1, Math.floor(frame / 15));
  const wordFrame = frame % 15;
  const iconIn = fade(frame, s(4.2), s(6.5));
  const shake = frame > s(6.5) ? (Math.random() - 0.5) * interpolate(frame, [s(6.5), s(8)], [0, 16]) : 0;
  return (
    <AbsoluteFill>
      <Background intensity={fade(frame, 0, s(2)) * 0.8} hue={C.red} />
      <DataRain color={C.red} count={18} />
      {frame < s(4) && (
        <AbsoluteFill style={{ alignItems: "center", justifyContent: "center" }}>
          <div style={{ transform: `scale(${interpolate(wordFrame, [0, 15], [1.25, 1])})`, filter: `blur(${interpolate(wordFrame, [0, 3], [8, 0], { extrapolateRight: "clamp" })}px)` }}>
            <SlamText
              key={index}
              text={WORDS[index]}
              start={index * 15}
              stagger={0.6}
              style={{ fontFamily: DISPLAY, fontWeight: 700, fontSize: index === 7 ? 230 : 180, letterSpacing: 8, color: index === 7 ? C.yellow : C.text, textShadow: `0 0 40px ${index === 7 ? C.yellow : C.red}66` }}
            />
          </div>
          <AbsoluteFill style={{ background: "#fff", opacity: wordFrame === 0 ? 0.18 : 0 }} />
        </AbsoluteFill>
      )}
      {frame >= s(4) && (
        <AbsoluteFill style={{ transform: `translate(${shake}px, ${shake * 0.6}px)` }}>
          <Rings x={960} y={470} size={interpolate(frame, [s(4), s(8)], [1400, 620])} color={C.cyan} opacity={iconIn * 0.8} />
          <AbsoluteFill style={{ alignItems: "center", justifyContent: "center", flexDirection: "column", gap: 40 }}>
            <Img
              src={staticFile("aegis-icon.png")}
              style={{
                width: 420,
                height: 420,
                opacity: iconIn,
                transform: `scale(${interpolate(frame, [s(4.2), s(8)], [0.5, 1.12])}) rotate(${interpolate(frame, [s(4.2), s(6.5)], [-30, 0], { extrapolateRight: "clamp" })}deg)`,
                filter: `drop-shadow(0 0 ${30 * iconIn}px ${C.cyan}aa)`,
              }}
            />
            <DecodeText text="ZU VIEL PAPIER?" start={s(4.6)} speed={1} style={{ fontFamily: MONO, fontSize: 44, letterSpacing: 12, color: C.yellow }} />
          </AbsoluteFill>
          <AbsoluteFill style={{ background: "#fff", opacity: fade(frame, s(7.6), s(8)) }} />
        </AbsoluteFill>
      )}
    </AbsoluteFill>
  );
}

// ------------------------------------------------------------------ 8-10 s

function Logo() {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const pop = spring({ frame, fps, config: { damping: 9, stiffness: 180 } });
  return (
    <AbsoluteFill>
      <Background />
      <AbsoluteFill style={{ background: "#fff", opacity: fade(frame, 0, 8, 0.9, 0) }} />
      <Rings x={960} y={480} size={1100} opacity={0.35} />
      <Bump amount={0.02}>
        <AbsoluteFill style={{ alignItems: "center", justifyContent: "center", flexDirection: "column", gap: 24 }}>
          <div style={{ display: "flex", alignItems: "center", gap: 40, transform: `scale(${interpolate(pop, [0, 1], [2.2, 1])})` }}>
            <Img src={staticFile("aegis-icon.png")} style={{ width: 230, height: 230, filter: `drop-shadow(0 0 20px ${C.cyan}88)` }} />
            <SlamText text="AEGIS" start={0} stagger={2} style={{ fontFamily: DISPLAY, fontWeight: 700, fontSize: 240, letterSpacing: 18, color: C.text, textShadow: `0 0 40px ${C.cyan}88` }} />
          </div>
          <DecodeText text="DER HAUSHALT, DER MITDENKT." start={12} speed={0.7} style={{ fontFamily: MONO, fontSize: 40, letterSpacing: 9, color: C.yellow }} />
        </AbsoluteFill>
      </Bump>
    </AbsoluteFill>
  );
}

// ------------------------------------------------------------------ 10-18 s

/** Photo, straightened, saved, then the list while the archive and the AI work. */
function Upload() {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const enter = spring({ frame, fps, config: { damping: 14 } });
  const second = s(segmentsLength(CUTS.upload.slice(0, 1)));
  return (
    <AbsoluteFill>
      <Background />
      <DataRain />
      <Rings x={1450} y={540} size={1000} opacity={0.3} />
      <Camera keys={[{ f: 0, scale: 1 }, { f: s(1.4), scale: 1.32, y: -60 }, { f: second - 4, scale: 1.32, y: -60 }, { f: second + 6, scale: 1, y: 0 }, { f: s(6), scale: 1.06 }]} origin="76% 40%">
        <Phone height={940} style={{ right: 290, top: 70 + interpolate(enter, [0, 1], [900, 0]), transform: `rotate(${interpolate(enter, [0, 1], [12, 0])}deg)` }}>
          <Segments src="phone-upload.mp4" segments={CUTS.upload.slice(0, 2)} />
          <Sweep at={s(1.3)} />
          <Sweep at={second + 2} />
        </Phone>
      </Camera>
      {frame < second ? (
        <Caption kicker="// 01 ERFASSEN" title={["FOTO.", "FERTIG."]} sub="Aegis findet die Blattkanten und begradigt die Seite." start={4} style={{ left: 170, top: 310 }} />
      ) : (
        <Caption kicker="// 02 ARCHIV" title={["SICHER", "GESPEICHERT."]} sub="Erst gespeichert, dann archiviert und per OCR durchsuchbar." start={second + 1} style={{ left: 170, top: 310 }} />
      )}
      <Callout at={s(1.6)} x={1250} y={330} dx={-200} dy={-110} text="KANTEN ERKANNT" color={C.cyan} />
      <Callout at={second + 18} x={1300} y={300} dx={-230} dy={-80} text="OCR ✓" />
    </AbsoluteFill>
  );
}

/** The classified letter: title, folder and labels, with callouts on each. */
function Classified() {
  const frame = useCurrentFrame();
  return (
    <AbsoluteFill>
      <Background hue={C.yellow} />
      <Camera keys={[{ f: 0, scale: 1.25, x: -40 }, { f: s(2), scale: 1.1, x: 0 }]} origin="70% 30%">
        <Phone height={940} glow={C.yellow} style={{ right: 290, top: 70 }}>
          <Segments src="phone-upload.mp4" segments={CUTS.upload.slice(2)} />
          <Sweep at={2} />
        </Phone>
      </Camera>
      <div style={{ position: "absolute", left: 160, top: 300 }}>
        <div style={{ fontFamily: MONO, fontSize: 24, letterSpacing: 6, color: C.cyan }}>// 03 KI-EINORDNUNG</div>
        <div style={{ marginTop: 20, display: "flex", flexDirection: "column", gap: 14 }}>
          {[
            ["TITEL", "Beitragsanpassung Kfz 2027"],
            ["ORDNER", "Versicherungen / KFZ"],
            ["LABELS", "Brief · Vulkan · KFZ"],
            ["DATUM", "01.10.2026"],
          ].map(([k, v], i) => (
            <div key={k} style={{ display: "flex", gap: 20, alignItems: "baseline", opacity: fade(frame, 4 + i * 7, 9 + i * 7), transform: `translateX(${interpolate(frame, [4 + i * 7, 12 + i * 7], [-60, 0], { extrapolateLeft: "clamp", extrapolateRight: "clamp" })}px)` }}>
              <span style={{ fontFamily: MONO, fontSize: 26, color: C.yellow, width: 130, letterSpacing: 4 }}>{k}</span>
              <span style={{ fontFamily: DISPLAY, fontWeight: 600, fontSize: 40, color: C.text }}>{v}</span>
              <span style={{ fontFamily: MONO, fontSize: 30, color: C.cyan, opacity: fade(frame, 9 + i * 7, 12 + i * 7) }}>✓</span>
            </div>
          ))}
        </div>
      </div>
    </AbsoluteFill>
  );
}

// ------------------------------------------------------------------ 18-22 s

/** The page editor: split a mailbox scan, turn a sideways page, rename, done. */
function Pages() {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const enter = spring({ frame, fps, config: { damping: 15 } });
  const marks = [0, ...CUTS.pages.map((_, i) => s(segmentsLength(CUTS.pages.slice(0, i + 1))))];
  return (
    <AbsoluteFill>
      <Background hue={C.cyan} />
      <DataRain />
      <Rings x={560} y={540} size={950} opacity={0.3} color={C.yellow} />
      <Camera keys={[{ f: 0, scale: 1.05, rotateY: 14 }, { f: s(4), scale: 1.15, rotateY: -6 }]} origin="30% 50%">
        <Phone height={940} style={{ left: 330, top: 70 + interpolate(enter, [0, 1], [-900, 0]) }}>
          <Segments src="phone-pages.mp4" segments={CUTS.pages} />
          {marks.slice(1, -1).map((m) => (
            <Sweep key={m} at={m} />
          ))}
        </Phone>
      </Camera>
      <div style={{ position: "absolute", left: 960, top: 300, display: "flex", flexDirection: "column", gap: 10 }}>
        <div style={{ fontFamily: MONO, fontSize: 24, letterSpacing: 6, color: C.cyan, marginBottom: 14 }}>// 04 SEITENEDITOR</div>
        {["TRENNEN.", "DREHEN.", "SORTIEREN."].map((word, i) => (
          <SlamText key={word} text={word} start={marks[Math.min(i, marks.length - 1)] + 3 + (i === 2 ? 12 : 0)} stagger={1} style={{ fontFamily: DISPLAY, fontWeight: 700, fontSize: 104, color: i === 1 ? C.yellow : C.text, letterSpacing: 4 }} />
        ))}
        <div style={{ fontFamily: DISPLAY, fontWeight: 500, fontSize: 32, color: C.text2, maxWidth: 640, marginTop: 14, opacity: fade(frame, 30, 42) }}>
          Ein Scan mit zwei Briefen wird zu zwei Dokumenten. Das Original bleibt erhalten.
        </div>
      </div>
    </AbsoluteFill>
  );
}

// ------------------------------------------------------------------ 22-24 s

function Deadlines() {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const count = Math.round(interpolate(frame, [4, 26], [99, 6], { extrapolateLeft: "clamp", extrapolateRight: "clamp" }));
  const pop = spring({ frame: frame - 26, fps, config: { damping: 8, stiffness: 200 } });
  return (
    <AbsoluteFill>
      <Background hue={C.yellow} />
      <Camera keys={[{ f: 0, scale: 1, rotateY: -14 }, { f: s(1.1), scale: 1.9, x: -750, y: 110, rotateY: 0 }, { f: s(2), scale: 2.0, x: -790, y: 112 }]} origin="70% 30%">
        <Browser clip={{ src: "desk-today.mp4", startFrom: CUTS.today.a, rate: CUTS.today.rate }} width={1240} style={{ right: 80, top: 150 }} />
      </Camera>
      <div style={{ position: "absolute", left: 120, bottom: 130, display: "flex", alignItems: "flex-end", gap: 30 }}>
        <div style={{ fontFamily: DISPLAY, fontWeight: 700, fontSize: 300, lineHeight: 0.8, color: C.yellow, textShadow: `0 0 60px ${C.yellow}88`, transform: `scale(${frame > 26 ? interpolate(pop, [0, 1], [1.4, 1]) : 1})`, transformOrigin: "left bottom" }}>
          {count}
        </div>
        <div style={{ display: "flex", flexDirection: "column", gap: 8, paddingBottom: 24 }}>
          <div style={{ fontFamily: MONO, fontSize: 34, letterSpacing: 8, color: C.text }}>TAGE BIS ZUR</div>
          <div style={{ fontFamily: MONO, fontSize: 34, letterSpacing: 8, color: C.text }}>KÜNDIGUNGSFRIST</div>
        </div>
      </div>
      <div style={{ position: "absolute", left: 120, top: 120 }}>
        <SlamText text="KEINE FRIST VERPASST." start={2} stagger={0.9} style={{ fontFamily: DISPLAY, fontWeight: 700, fontSize: 72, color: C.text, letterSpacing: 4, textShadow: `0 0 30px ${C.void}` }} />
      </div>
    </AbsoluteFill>
  );
}

// ------------------------------------------------------------------ 24-28 s

function AskBreak() {
  const frame = useCurrentFrame();
  const zoom = interpolate(frame, [0, s(4)], [1, 1.25]);
  return (
    <AbsoluteFill>
      <Background hue={C.red} intensity={0.6} />
      <Rings x={960} y={540} size={interpolate(frame, [0, s(4)], [700, 1600])} color={C.red} opacity={0.35} />
      <AbsoluteFill style={{ alignItems: "center", justifyContent: "center", flexDirection: "column", gap: 20, transform: `scale(${zoom})` }}>
        <SlamText text="FRAG" start={2} stagger={7.5} style={{ fontFamily: DISPLAY, fontWeight: 700, fontSize: 230, letterSpacing: 24, color: C.text }} />
        <SlamText text="AEGIS." start={32} stagger={5} style={{ fontFamily: DISPLAY, fontWeight: 700, fontSize: 230, letterSpacing: 24, color: C.yellow, textShadow: `0 0 50px ${C.yellow}88` }} />
        <div style={{ fontFamily: MONO, fontSize: 30, letterSpacing: 6, color: C.cyan, opacity: fade(frame, 72, 84) }}>
          <DecodeText text="ANTWORTEN AUS EUREN EIGENEN UNTERLAGEN" start={72} speed={0.5} split={false} />
        </div>
      </AbsoluteFill>
    </AbsoluteFill>
  );
}

// ------------------------------------------------------------------ 28-36 s

function Chat() {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const enter = spring({ frame, fps, config: { damping: 15 } });
  const answer = s(CUTS.chat.zoomAt);
  return (
    <AbsoluteFill>
      <Background />
      <DataRain />
      <Camera
        keys={[{ f: 0, scale: 0.9, rotateY: 18, x: 120 }, { f: s(1), scale: 1, rotateY: 0, x: 0 }, { f: answer, scale: 1.05 }, { f: answer + 14, scale: 1.45, x: -80 }, { f: s(6), scale: 1.52, x: -90 }]}
        origin="75% 52%"
      >
        <Browser clip={{ src: "desk-chat.mp4", startFrom: CUTS.chat.a, rate: CUTS.chat.rate }} width={1300} glow={C.cyan} style={{ left: 520, top: 150, opacity: enter }}>
          <Sweep at={answer} />
        </Browser>
      </Camera>
      <Callout at={answer + 16} x={450} y={566} dx={-60} dy={-150} text="QUELLE · S. 2 ✓" color={C.cyan} />
      <Callout at={answer + 26} x={450} y={652} dx={-60} dy={150} text="QUELLE · S. 1 ✓" color={C.yellow} />
      <div style={{ opacity: 1 - fade(frame, answer - 4, answer + 6) }}>
        <Caption kicker="// 05 KI-ASSISTENT" title={["MIT", "QUELLEN."]} sub="Jede Antwort nennt Dokument und Seite. Geprüft, nicht geraten." start={4} size={84} style={{ left: 110, top: 330 }} />
      </div>
    </AbsoluteFill>
  );
}

// ------------------------------------------------------------------ 36-38 s

function ChatPhone() {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const enter = spring({ frame, fps, config: { damping: 14 } });
  return (
    <AbsoluteFill>
      <Background hue={C.cyan} />
      <Rings x={1300} y={540} size={1000} opacity={0.35} />
      <Phone height={960} clip={{ src: "phone-chat.mp4", startFrom: CUTS.phoneChat.a, rate: CUTS.phoneChat.rate }} style={{ left: 1075, top: 60 + interpolate(enter, [0, 1], [700, 0]), transform: `rotate(${interpolate(enter, [0, 1], [-10, 0])}deg)` }}>
        <Sweep at={10} />
      </Phone>
      <div style={{ position: "absolute", left: 150, top: 380, display: "flex", flexDirection: "column", gap: 10 }}>
        <SlamText text="AUCH" start={2} stagger={1} style={{ fontFamily: DISPLAY, fontWeight: 700, fontSize: 110, color: C.text }} />
        <SlamText text="UNTERWEGS." start={9} stagger={1} style={{ fontFamily: DISPLAY, fontWeight: 700, fontSize: 110, color: C.yellow }} />
      </div>
    </AbsoluteFill>
  );
}

// ------------------------------------------------------------------ 38-42 s: feature montage, a shot every second

function MontageShot({ src, startFrom, rate, tag, kind, zoomOrigin }: { src: string; startFrom: number; rate: number; tag: string; kind: EnterKind; zoomOrigin: string }) {
  return (
    <AbsoluteFill>
      <Background hue={C.yellow} intensity={0.7} />
      <Enter kind={kind} length={6}>
        <Camera keys={[{ f: 0, scale: 1.15 }, { f: 30, scale: 1.32 }]} origin={zoomOrigin}>
          <Browser clip={{ src, startFrom, rate }} width={1500} style={{ left: 210, top: 90 }} />
        </Camera>
      </Enter>
      <Tag text={tag} start={2} />
    </AbsoluteFill>
  );
}

function Montage() {
  const shots = CUTS.montage;
  return (
    <AbsoluteFill>
      {shots.map((shot, i) => (
        <Sequence key={shot.tag} from={i * 30} durationInFrames={30}>
          <MontageShot {...shot} kind={(["whip", "stripes", "zoom", "slice"] as EnterKind[])[i % 4]} />
        </Sequence>
      ))}
      {shots.map((_, i) => (i > 0 ? <GlitchBurst key={i} at={i * 30} length={6} /> : null))}
      <div style={{ position: "absolute", right: 100, top: 92, padding: "8px 18px", background: "#07080b", border: `1px solid ${C.cyan}`, fontFamily: MONO, fontSize: 28, letterSpacing: 8, color: C.cyan, zIndex: 5 }}>
        <DecodeText text="// UND NOCH MEHR" start={0} speed={0.6} split={false} />
      </div>
    </AbsoluteFill>
  );
}

// ------------------------------------------------------------------ 42-48 s

function Notify() {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const split = s(3.0);
  const enter = spring({ frame, fps, config: { damping: 14 } });
  const toSplit = spring({ frame: frame - split, fps, config: { damping: 16 } });
  return (
    <AbsoluteFill>
      <Background hue={C.cyan} />
      <DataRain />
      <Browser
        clip={{ src: "desk-documents.mp4", startFrom: CUTS.documents.a, rate: 1.4 }}
        width={1180}
        glow={C.yellow}
        style={{ left: 120, top: 190, opacity: toSplit, transform: `translateX(${interpolate(toSplit, [0, 1], [-500, 0])}px) perspective(1600px) rotateY(${interpolate(toSplit, [0, 1], [30, 6])}deg)` }}
      />
      <Camera keys={[{ f: 0, scale: 1 }, { f: s(0.9), scale: 1 }, { f: s(1.3), scale: 1.55, y: 260 }, { f: s(2.4), scale: 1.55, y: 260 }, { f: s(2.8), scale: 1, y: 0 }]} origin="70% 10%">
        <Phone
          height={900}
          clip={{ src: "phone-today.mp4", startFrom: CUTS.today.phone, rate: 1 }}
          style={{ left: interpolate(toSplit, [0, 1], [1100, 1380]), top: 90 + interpolate(enter, [0, 1], [700, 0]) }}
        >
          <PushNotice start={s(0.8)} end={s(2.5)} body="Kündigung für „Iron Grid Fitness“ bis 09.10.2026 prüfen." />
          <Tap at={s(2.3)} x={200} y={110} />
        </Phone>
      </Camera>
      <div style={{ opacity: 1 - toSplit }}>
        <Caption kicker="// 06 ERINNERN" title={["RECHTZEITIG", "ERINNERT."]} sub="Push aufs Handy, bevor eine Frist abläuft." start={4} size={84} style={{ left: 150, top: 330 }} />
      </div>
      {frame >= split && (
        <div style={{ position: "absolute", left: 140, top: 92, display: "flex", gap: 24, alignItems: "baseline" }}>
          <SlamText text="HANDY & DESKTOP." start={split + 4} stagger={0.8} style={{ fontFamily: DISPLAY, fontWeight: 700, fontSize: 64, color: C.text, letterSpacing: 4 }} />
          <DecodeText text="FÜR BEIDE IM HAUSHALT." start={split + 20} speed={0.6} split={false} style={{ fontFamily: MONO, fontSize: 28, color: C.cyan, letterSpacing: 4 }} />
        </div>
      )}
      <GlitchBurst at={split} length={8} />
    </AbsoluteFill>
  );
}

// ------------------------------------------------------------------ 48-56 s

function EndCard() {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const pop = spring({ frame, fps, config: { damping: 9, stiffness: 160 } });
  const out = fade(frame, s(6.6), s(8), 1, 0);
  const chips = ["DOKUMENTE", "SEITENEDITOR", "VERTRÄGE", "AUFGABEN", "PUSH", "KI-ASSISTENT"];
  return (
    <AbsoluteFill style={{ opacity: out }}>
      <Background hue={C.cyan} />
      <AbsoluteFill style={{ background: "#fff", opacity: fade(frame, 0, 8, 0.85, 0) }} />
      <Rings x={960} y={330} size={interpolate(frame, [0, s(8)], [520, 760])} opacity={0.5} />
      <AbsoluteFill style={{ alignItems: "center", justifyContent: "center", flexDirection: "column", gap: 30 }}>
        <Img src={staticFile("aegis-icon.png")} style={{ width: 300, height: 300, transform: `scale(${pop}) rotate(${interpolate(pop, [0, 1], [-25, 0])}deg)`, filter: `drop-shadow(0 0 30px ${C.cyan}88)` }} />
        <SlamText text="AEGIS" start={4} stagger={3} style={{ fontFamily: DISPLAY, fontWeight: 700, fontSize: 180, letterSpacing: 26, color: C.text, textShadow: `0 0 40px ${C.cyan}88` }} />
        <DecodeText text="EUER HAUSHALT. UNTER KONTROLLE." start={22} speed={0.6} style={{ fontFamily: MONO, fontSize: 40, letterSpacing: 8, color: C.yellow }} />
        <div style={{ display: "flex", gap: 16, marginTop: 10 }}>
          {chips.map((item, i) => {
            const t = frame - 45 - i * 4;
            const sp = spring({ frame: t, fps, config: { damping: 12, stiffness: 200 } });
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
                  opacity: t < 0 ? 0 : sp,
                  transform: `translateY(${interpolate(sp, [0, 1], [40, 0])}px)`,
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

const SCENES: { from: number; to: number; label: string; Component: () => JSX.Element; enter?: EnterKind }[] = [
  { from: 0, to: 8, label: "BOOT", Component: Intro },
  { from: 8, to: 10, label: "INIT", Component: Logo },
  { from: 10, to: 16, label: "UPLOAD", Component: Upload, enter: "whip" },
  { from: 16, to: 18, label: "KLASSIFIKATION", Component: Classified, enter: "stripes" },
  { from: 18, to: 22, label: "SEITEN", Component: Pages, enter: "slice" },
  { from: 22, to: 24, label: "FRISTEN", Component: Deadlines, enter: "zoom" },
  { from: 24, to: 28, label: "QUERY", Component: AskBreak, enter: "stripes" },
  { from: 28, to: 36, label: "ASSISTENT", Component: Chat, enter: "zoom" },
  { from: 36, to: 38, label: "MOBIL", Component: ChatPhone, enter: "whip" },
  { from: 38, to: 42, label: "FEATURES", Component: Montage },
  { from: 42, to: 48, label: "PUSH", Component: Notify, enter: "slice" },
  { from: 48, to: PROMO_SECONDS, label: "END", Component: EndCard },
];

export function Promo() {
  useFonts();
  const frame = useCurrentFrame();
  const cuts = SCENES.slice(1).map((scene) => s(scene.from));
  const current = SCENES.find((scene) => frame >= s(scene.from) && frame < s(scene.to)) ?? SCENES[0];
  return (
    <AbsoluteFill style={{ background: C.void }}>
      <Audio src={staticFile("soundtrack.wav")} />
      <Shake moments={cuts}>
        {SCENES.map(({ from, to, Component, label, enter }) => (
          <Sequence key={label} from={s(from)} durationInFrames={s(to) - s(from)}>
            {enter ? (
              <Enter kind={enter}>
                <Component />
              </Enter>
            ) : (
              <Component />
            )}
          </Sequence>
        ))}
      </Shake>
      {cuts.map((at) => (
        <GlitchBurst key={at} at={at} />
      ))}
      {current.label !== "END" && current.label !== "BOOT" && <Hud label={current.label} />}
      <Overlay />
    </AbsoluteFill>
  );
}
