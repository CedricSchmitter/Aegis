import type { JSX } from "react";
import { AbsoluteFill, Audio, Img, interpolate, Sequence, spring, staticFile, useCurrentFrame, useVideoConfig } from "remotion";
import { Background, Browser, C, Caption, Clip, DecodeText, DISPLAY, fade, GlitchBurst, Hud, MONO, Overlay, Phone, PushNotice, Shake, Tap, useFonts } from "./fx";
import { CUTS, PROMO_SECONDS, segmentsLength, type Segment } from "./timeline";

export const PROMO_FRAMES = PROMO_SECONDS * 30;
const s = (seconds: number) => Math.round(seconds * 30);

/** 0-8 s: words on the beat, then the shield builds up for the impact. */
function Intro() {
  const frame = useCurrentFrame();
  const words = [
    { text: "BRIEFE.", at: 1.0 },
    { text: "RECHNUNGEN.", at: 2.0 },
    { text: "VERTRÄGE.", at: 3.0 },
    { text: "FRISTEN.", at: 4.0 },
  ];
  const current = [...words].reverse().find((w) => frame >= s(w.at));
  const icon = fade(frame, s(5.6), s(7.8));
  return (
    <AbsoluteFill>
      <Background intensity={fade(frame, 0, s(2))} hue={C.red} />
      <div style={{ position: "absolute", left: 140, top: 150, fontFamily: MONO, fontSize: 22, letterSpacing: 4, color: C.cyan, opacity: fade(frame, 4, 12) }}>
        <DecodeText text="> HAUSHALT.SYS — SCAN …" start={4} speed={0.8} split={false} />
      </div>
      {current && frame < s(5.4) && (
        <AbsoluteFill style={{ alignItems: "center", justifyContent: "center" }}>
          <DecodeText key={current.text} text={current.text} start={s(current.at)} speed={0.9} style={{ fontFamily: DISPLAY, fontWeight: 700, fontSize: 170, letterSpacing: 8, color: C.text }} />
        </AbsoluteFill>
      )}
      {frame >= s(5.4) && (
        <AbsoluteFill style={{ alignItems: "center", justifyContent: "center", flexDirection: "column", gap: 30 }}>
          <Img
            src={staticFile("aegis-icon.png")}
            style={{
              width: 420,
              height: 420,
              opacity: icon,
              transform: `scale(${interpolate(frame, [s(5.4), s(8)], [0.6, 1.08])})`,
              filter: `drop-shadow(0 0 ${24 * icon}px ${C.cyan}88)`,
            }}
          />
          <DecodeText text="ZU VIEL PAPIER?" start={s(5.5)} speed={1} style={{ fontFamily: MONO, fontSize: 40, letterSpacing: 10, color: C.yellow }} />
        </AbsoluteFill>
      )}
      <GlitchBurst at={s(2)} length={6} />
      <GlitchBurst at={s(4)} length={6} />
    </AbsoluteFill>
  );
}

/** 8-10 s: the wordmark slams in on the impact. */
function Logo() {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const pop = spring({ frame, fps, config: { damping: 9, stiffness: 160 } });
  return (
    <AbsoluteFill>
      <Background />
      <AbsoluteFill style={{ alignItems: "center", justifyContent: "center", flexDirection: "column", gap: 24 }}>
        <div style={{ display: "flex", alignItems: "center", gap: 40, transform: `scale(${interpolate(pop, [0, 1], [1.8, 1])})` }}>
          <Img src={staticFile("aegis-icon.png")} style={{ width: 230, height: 230, filter: `drop-shadow(0 0 20px ${C.cyan}88)` }} />
          <DecodeText text="AEGIS" start={0} speed={1.2} style={{ fontFamily: DISPLAY, fontWeight: 700, fontSize: 240, letterSpacing: 18, color: C.text, textShadow: `0 0 40px ${C.cyan}88` }} />
        </div>
        <DecodeText text="DER HAUSHALT, DER MITDENKT." start={10} speed={0.9} style={{ fontFamily: MONO, fontSize: 40, letterSpacing: 9, color: C.yellow }} />
      </AbsoluteFill>
    </AbsoluteFill>
  );
}

/** 10-20 s: photo of a letter, straightened, stored and classified on the phone. */
function Upload() {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const enter = spring({ frame, fps, config: { damping: 16 } });
  const second = s(segmentsLength(CUTS.upload.slice(0, 1)));
  return (
    <AbsoluteFill>
      <Background />
      <Phone
        height={940}
        style={{ right: 300, top: 70 + interpolate(enter, [0, 1], [900, 0]), transform: `rotate(${interpolate(enter, [0, 1], [8, 0])}deg)` }}
      >
        <Segments src="phone-upload.mp4" segments={CUTS.upload} />
      </Phone>
      {frame < second && <Caption kicker="// 01 ERFASSEN" title={["FOTO.", "FERTIG."]} sub="Brief abfotografieren, Aegis begradigt die Seite und speichert sicher." start={6} style={{ left: 170, top: 300 }} />}
      {frame >= second && (
        <Caption kicker="// 02 EINORDNEN" title={["KI ORDNET", "EIN."]} sub="Titel, Ordner und Labels erkennt Aegis selbst. Du behältst das letzte Wort." start={second + 2} style={{ left: 170, top: 300 }} />
      )}
      <GlitchBurst at={second} length={8} />
    </AbsoluteFill>
  );
}
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

/** 20-24 s: deadlines on the desktop. */
function Deadlines() {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const enter = spring({ frame, fps, config: { damping: 18 } });
  return (
    <AbsoluteFill>
      <Background hue={C.yellow} />
      <Browser
        clip={{ src: "desk-today.mp4", startFrom: CUTS.today.a, rate: CUTS.today.rate }}
        width={1180}
        style={{ right: 70, top: 170, transform: `perspective(1600px) rotateY(${interpolate(enter, [0, 1], [-25, -8])}deg) translateX(${interpolate(enter, [0, 1], [400, 0])}px)` }}
      />
      <Caption kicker="// 03 FRISTEN" title={["KEINE FRIST", "VERPASST."]} sub="Kündigungstermine rechnet Aegis aus den bestätigten Vertragsangaben aus." start={3} size={72} style={{ left: 120, top: 330, maxWidth: 560 }} />
    </AbsoluteFill>
  );
}

/** 24-28 s: breakdown, a single question. */
function AskBreak() {
  const frame = useCurrentFrame();
  return (
    <AbsoluteFill>
      <Background hue={C.red} intensity={0.6} />
      <AbsoluteFill style={{ alignItems: "center", justifyContent: "center", flexDirection: "column", gap: 30 }}>
        <DecodeText text="FRAG" start={4} speed={2} style={{ fontFamily: DISPLAY, fontWeight: 700, fontSize: 210, letterSpacing: 20, color: C.text }} />
        <DecodeText text="AEGIS." start={34} speed={2} style={{ fontFamily: DISPLAY, fontWeight: 700, fontSize: 210, letterSpacing: 20, color: C.yellow, textShadow: `0 0 40px ${C.yellow}88` }} />
        <div style={{ fontFamily: MONO, fontSize: 30, letterSpacing: 6, color: C.cyan, opacity: fade(frame, 70, 85) }}>
          <DecodeText text="ANTWORTEN AUS EUREN EIGENEN UNTERLAGEN" start={70} speed={0.6} split={false} />
        </div>
      </AbsoluteFill>
    </AbsoluteFill>
  );
}

/** 28-40 s: the assistant answers with verified sources. */
function Chat() {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const enter = spring({ frame, fps, config: { damping: 16 } });
  const zoomStart = s(CUTS.chat.zoomAt);
  const zoom = interpolate(frame, [zoomStart, zoomStart + 30], [1, 1.35], { extrapolateLeft: "clamp", extrapolateRight: "clamp" });
  return (
    <AbsoluteFill>
      <Background />
      <AbsoluteFill style={{ transform: `scale(${zoom})`, transformOrigin: "75% 50%" }}>
        <Browser
          clip={{ src: "desk-chat.mp4", startFrom: CUTS.chat.a, rate: CUTS.chat.rate }}
          width={1300}
          glow={C.cyan}
          style={{ left: 520, top: 150, transform: `scale(${interpolate(enter, [0, 1], [0.7, 1])})`, opacity: enter }}
        />
      </AbsoluteFill>
      <div style={{ opacity: 1 - fade(frame, zoomStart - 6, zoomStart + 6) }}>
        <Caption kicker="// 04 FRAGEN" title={["MIT", "QUELLEN."]} sub="Jede Antwort nennt das Dokument und die Seite, auf der es steht." start={6} size={84} style={{ left: 110, top: 330 }} />
      </div>
    </AbsoluteFill>
  );
}

/** 40-48 s: the push notification arrives, then phone and desktop side by side. */
function Notify() {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const split = s(4.2);
  const enter = spring({ frame, fps, config: { damping: 16 } });
  const toSplit = spring({ frame: frame - split, fps, config: { damping: 18 } });
  return (
    <AbsoluteFill>
      <Background hue={C.cyan} />
      <Browser
        clip={{ src: "desk-documents.mp4", startFrom: CUTS.documents.a, rate: 1 }}
        width={1180}
        glow={C.yellow}
        style={{ left: 120, top: 190, opacity: toSplit, transform: `translateX(${interpolate(toSplit, [0, 1], [-300, 0])}px)` }}
      />
      <Phone
        height={900}
        clip={{ src: "phone-today.mp4", startFrom: CUTS.today.phone, rate: 1 }}
        style={{ left: interpolate(toSplit, [0, 1], [1100, 1380]), top: 90 + interpolate(enter, [0, 1], [700, 0]) }}
      >
        <PushNotice start={s(1.0)} end={s(2.8)} body="Kündigung für „Iron Grid Fitness“ bis 09.10.2026 prüfen." />
        <Tap at={s(2.6)} x={200} y={110} />
      </Phone>
      <div style={{ opacity: 1 - toSplit }}>
        <Caption kicker="// 05 ERINNERN" title={["RECHTZEITIG", "ERINNERT."]} sub="Push aufs Handy, bevor eine Kündigungsfrist abläuft." start={8} size={84} style={{ left: 150, top: 330 }} />
      </div>
      {frame >= split && (
        <div style={{ position: "absolute", left: 140, top: 92, display: "flex", gap: 24, alignItems: "baseline" }}>
          <DecodeText text="HANDY & DESKTOP." start={split + 6} speed={1} style={{ fontFamily: DISPLAY, fontWeight: 700, fontSize: 64, color: C.text, letterSpacing: 4 }} />
          <DecodeText text="FÜR BEIDE IM HAUSHALT." start={split + 22} speed={0.8} split={false} style={{ fontFamily: MONO, fontSize: 28, color: C.cyan, letterSpacing: 4 }} />
        </div>
      )}
      <GlitchBurst at={split} length={8} />
    </AbsoluteFill>
  );
}

/** 48-56 s: end card. */
function EndCard() {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const pop = spring({ frame, fps, config: { damping: 10, stiffness: 140 } });
  const out = fade(frame, s(6.4), s(8), 1, 0);
  return (
    <AbsoluteFill style={{ opacity: out }}>
      <Background hue={C.cyan} />
      <AbsoluteFill style={{ alignItems: "center", justifyContent: "center", flexDirection: "column", gap: 34 }}>
        <Img src={staticFile("aegis-icon.png")} style={{ width: 300, height: 300, transform: `scale(${pop})`, filter: `drop-shadow(0 0 30px ${C.cyan}88)` }} />
        <DecodeText text="AEGIS" start={4} speed={1.4} style={{ fontFamily: DISPLAY, fontWeight: 700, fontSize: 180, letterSpacing: 26, color: C.text, textShadow: `0 0 40px ${C.cyan}88` }} />
        <DecodeText text="EUER HAUSHALT. UNTER KONTROLLE." start={18} speed={0.8} style={{ fontFamily: MONO, fontSize: 40, letterSpacing: 8, color: C.yellow }} />
        <div style={{ display: "flex", gap: 18, marginTop: 10, opacity: fade(frame, 50, 64) }}>
          {["DOKUMENTE", "VERTRÄGE", "AUFGABEN", "KI-ASSISTENT"].map((item) => (
            <div key={item} style={{ fontFamily: MONO, fontSize: 24, letterSpacing: 4, color: C.cyan, border: `2px solid ${C.cyan}88`, padding: "10px 20px", clipPath: "polygon(0 0, calc(100% - 14px) 0, 100% 14px, 100% 100%, 14px 100%, 0 calc(100% - 14px))" }}>
              {item}
            </div>
          ))}
        </div>
      </AbsoluteFill>
    </AbsoluteFill>
  );
}

const SCENES: { from: number; to: number; label: string; Component: () => JSX.Element }[] = [
  { from: 0, to: 8, label: "BOOT", Component: Intro },
  { from: 8, to: 10, label: "INIT", Component: Logo },
  { from: 10, to: 20.5, label: "UPLOAD", Component: Upload },
  { from: 20.5, to: 24, label: "FRISTEN", Component: Deadlines },
  { from: 24, to: 28, label: "QUERY", Component: AskBreak },
  { from: 28, to: 40, label: "ASSISTENT", Component: Chat },
  { from: 40, to: 48, label: "PUSH", Component: Notify },
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
        {SCENES.map(({ from, to, Component, label }) => (
          <Sequence key={label} from={s(from)} durationInFrames={s(to) - s(from)}>
            <Component />
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
