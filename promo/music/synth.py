"""Original synthwave soundtrack for the promo video, synthesized from scratch with numpy/scipy.

No samples or third-party recordings are used, so the track carries no third-party license. 120 BPM in A minor,
56 seconds, structured to match the video's cuts (one bar = 2 seconds):

  bars  0-3   intro: pad, filtered arpeggio, riser into an impact at 8 s
  bars  4-11  groove: drums, offbeat bass, arpeggio
  bars 12-13  breakdown with a riser
  bars 14-23  drop: full groove plus lead melody
  bars 24-27  outro: final hit at 48 s, pad tail

Usage: python3 music/synth.py [out.wav]
"""
import sys

import numpy as np
from scipy import signal
from scipy.io import wavfile

SR = 48_000
BPM = 120
BEAT = 60 / BPM
BAR = 4 * BEAT
LENGTH = 56.0
N = int(LENGTH * SR)
rng = np.random.default_rng(2077)


def t_axis(seconds):
    return np.arange(int(seconds * SR)) / SR


def midi(note):
    return 440.0 * 2 ** ((note - 69) / 12)


def saw(freq, seconds, phase=0.0):
    t = t_axis(seconds)
    return signal.sawtooth(2 * np.pi * freq * t + phase)


def lowpass(x, cutoff, order=2):
    b, a = signal.butter(order, min(cutoff, SR * 0.45) / (SR / 2), "low")
    return signal.lfilter(b, a, x)


def highpass(x, cutoff, order=2):
    b, a = signal.butter(order, cutoff / (SR / 2), "high")
    return signal.lfilter(b, a, x)


def bandpass(x, lo, hi, order=2):
    b, a = signal.butter(order, [lo / (SR / 2), hi / (SR / 2)], "band")
    return signal.lfilter(b, a, x)


def env_adsr(n, a=0.005, d=0.1, s=0.6, r=0.1):
    total = n / SR
    t = np.arange(n) / SR
    e = np.where(t < a, t / a, s + (1 - s) * np.exp(-(t - a) / max(d, 1e-4)))
    release_start = max(total - r, a)
    e = np.where(t > release_start, e * np.clip(1 - (t - release_start) / r, 0, 1), e)
    return e


def place(track, sound, start):
    i = int(start * SR)
    if i >= len(track):
        return
    end = min(len(track), i + len(sound))
    track[i:end] += sound[: end - i]


def stereo(mono, pan=0.0):
    left = mono * np.sqrt((1 - pan) / 2)
    right = mono * np.sqrt((1 + pan) / 2)
    return np.stack([left, right], axis=1)


def reverb(x, seconds=2.2, mix=0.3, seed=1):
    """Convolution with decaying stereo noise; x is (n, 2)."""
    r = np.random.default_rng(seed)
    n = int(seconds * SR)
    decay = np.exp(-np.arange(n) / SR * 6.5 / seconds)
    out = np.zeros_like(x)
    for ch in range(2):
        ir = r.standard_normal(n) * decay
        ir = lowpass(ir, 6000)
        ir /= np.sqrt(np.sum(ir**2))
        out[:, ch] = signal.fftconvolve(x[:, ch], ir)[: len(x)]
    return x * (1 - mix) + out * mix


def delay(x, seconds, feedback=0.35, mix=0.3, pingpong=True):
    d = int(seconds * SR)
    out = x.copy()
    tap = x.copy()
    for i in range(1, 6):
        shifted = np.zeros_like(x)
        shifted[d * i :] = tap[: len(x) - d * i] * feedback**i
        if pingpong and i % 2 == 1:
            shifted = shifted[:, ::-1]
        out += shifted * mix
    return out


# ------------------------------------------------------------------ instruments

def kick():
    t = t_axis(0.45)
    freq = 45 + 110 * np.exp(-t * 28)
    phase = 2 * np.pi * np.cumsum(freq) / SR
    body = np.sin(phase) * np.exp(-t * 7)
    click = highpass(rng.standard_normal(len(t)), 2000) * np.exp(-t * 300) * 0.3
    return np.tanh(1.6 * (body + click))


def snare():
    t = t_axis(0.35)
    noise = bandpass(rng.standard_normal(len(t)), 1200, 9000) * np.exp(-t * 14)
    tone = np.sin(2 * np.pi * 185 * t) * np.exp(-t * 25)
    return 0.7 * noise + 0.5 * tone


def hat(open_=False):
    t = t_axis(0.25 if open_ else 0.06)
    noise = highpass(rng.standard_normal(len(t)), 8000, order=4)
    return noise * np.exp(-t * (14 if open_ else 70))


def clap():
    t = t_axis(0.3)
    noise = bandpass(rng.standard_normal(len(t)), 900, 5000)
    e = np.zeros(len(t))
    for offset in (0, 0.011, 0.022):
        i = int(offset * SR)
        e[i:] += np.exp(-(t[: len(t) - i]) * 30)
    return noise * e * 0.6


def bass_note(note, seconds):
    f = midi(note)
    n = int(seconds * SR)
    raw = saw(f, seconds) + 0.5 * saw(f * 1.005, seconds) + 0.6 * np.sin(2 * np.pi * f / 2 * t_axis(seconds))
    t = np.arange(n) / SR
    cutoff = 300 + 1600 * np.exp(-t * 18)
    # Piecewise filter sweep: filter in short blocks with a falling cutoff.
    out = np.zeros(n)
    block = 512
    zi = None
    for i in range(0, n, block):
        b, a = signal.butter(2, min(cutoff[i], 20000) / (SR / 2), "low")
        if zi is None:
            zi = signal.lfilter_zi(b, a) * 0
        out[i : i + block], zi = signal.lfilter(b, a, raw[i : i + block], zi=zi)
    return np.tanh(1.5 * out) * env_adsr(n, 0.003, 0.15, 0.7, 0.03)


def pluck(note, seconds=0.22, bright=1.0):
    f = midi(note)
    n = int(seconds * SR)
    t = np.arange(n) / SR
    square = signal.square(2 * np.pi * f * t, duty=0.35) + 0.4 * saw(f * 2.002, seconds)
    return lowpass(square, 900 + 3500 * bright) * np.exp(-t * 14)


def pad_chord(notes, seconds):
    n = int(seconds * SR)
    t = np.arange(n) / SR
    x = np.zeros(n)
    for note in notes:
        f = midi(note)
        for detune in (-0.12, -0.05, 0, 0.05, 0.12):
            x += saw(f * 2 ** (detune / 12), seconds, phase=rng.uniform(0, 2 * np.pi))
    x = lowpass(x / (len(notes) * 5), 2200)
    swell = np.clip(t / 0.6, 0, 1) * np.clip((seconds - t) / 0.4, 0, 1)
    return x * swell


def lead_note(note, seconds):
    f = midi(note)
    n = int(seconds * SR)
    t = np.arange(n) / SR
    vib = 1 + 0.004 * np.sin(2 * np.pi * 5.5 * t) * np.clip((t - 0.15) / 0.2, 0, 1)
    phase = 2 * np.pi * np.cumsum(f * vib) / SR
    x = signal.sawtooth(phase) + 0.6 * signal.sawtooth(phase * 1.003) + 0.3 * signal.square(phase / 2)
    return lowpass(x, 3200) * env_adsr(n, 0.01, 0.3, 0.75, 0.08)


def riser(seconds):
    n = int(seconds * SR)
    t = np.arange(n) / SR
    noise = rng.standard_normal(n)
    out = np.zeros(n)
    block = 1024
    for i in range(0, n, block):
        lo = 300 + 6000 * (t[i] / seconds) ** 2
        out[i : i + block] = bandpass(noise[i : i + block + 0], lo, min(lo * 3, 20000))[: len(noise[i : i + block])]
    sweep = np.sin(2 * np.pi * np.cumsum(200 + 1200 * (t / seconds) ** 2) / SR) * 0.25
    return (out * 0.6 + sweep) * (t / seconds) ** 2


def impact():
    t = t_axis(3.0)
    boom_f = 30 + 60 * np.exp(-t * 6)
    boom = np.sin(2 * np.pi * np.cumsum(boom_f) / SR) * np.exp(-t * 1.6)
    crash = highpass(rng.standard_normal(len(t)), 3000) * np.exp(-t * 2.2) * 0.35
    return np.tanh(1.8 * boom) + crash


def glitch(seconds=0.25):
    """A bit-crushed stutter used on cuts."""
    t = t_axis(seconds)
    src = signal.square(2 * np.pi * 220 * t) * np.sin(2 * np.pi * 37 * t)
    crushed = np.round(src * 4) / 4
    gate = (np.floor(t * 32) % 2 == 0).astype(float)
    return crushed * gate * 0.3


# ------------------------------------------------------------------ arrangement

PROGRESSION = [  # A minor: Am, F, C, G (root notes for bass, chord tones for pad and arpeggio)
    (45, [57, 60, 64]),
    (41, [53, 57, 60]),
    (48, [55, 60, 64]),
    (43, [55, 59, 62]),
]

drums = np.zeros(N)
bass = np.zeros(N)
arp = np.zeros(N)
pads = np.zeros(N)
lead = np.zeros(N)
fx = np.zeros(N)
sidechain = np.ones(N)

K, S, H, HO, C = kick(), snare(), hat(), hat(True), clap()
duck = 1 - 0.65 * np.exp(-np.arange(int(0.3 * SR)) / SR * 14)

for bar in range(28):
    start = bar * BAR
    root, chord = PROGRESSION[bar % 4]
    groove = 4 <= bar < 12 or 14 <= bar < 24
    full = 14 <= bar < 24

    if bar < 24:
        place(pads, pad_chord(chord + [chord[0] + 12], BAR + 0.3), start)
    elif bar == 24:
        # Outro: one long A minor chord under the end card.
        place(pads, pad_chord([45, 57, 60, 64, 69], 7.5) * 1.3, start)

    # Arpeggio: 16ths over chord tones; filtered in the intro, open later.
    if bar < 24:
        pattern = [0, 1, 2, 3, 2, 1, 0, 2, 1, 3, 2, 1, 0, 1, 2, 3]
        tones = chord + [chord[0] + 12]
        bright = 0.15 + 0.85 * min(1, bar / 4) if bar < 12 else (0.4 if bar < 14 else 1.0)
        for i, p in enumerate(pattern):
            place(arp, pluck(tones[p] + 12, 0.2, bright) * (0.8 if i % 4 else 1.0), start + i * BEAT / 4)

    if groove:
        for beat in range(4):
            place(drums, K, start + beat * BEAT)
            i = int((start + beat * BEAT) * SR)
            end = min(N, i + len(duck))
            sidechain[i:end] = np.minimum(sidechain[i:end], duck[: end - i])
            if beat in (1, 3):
                place(drums, S * 0.9, start + beat * BEAT)
                if full:
                    place(drums, C * 0.7, start + beat * BEAT)
            for sixteenth in range(4):
                when = start + beat * BEAT + sixteenth * BEAT / 4
                if sixteenth == 2:
                    place(drums, HO * 0.16, when)
                else:
                    place(drums, H * (0.14 if sixteenth == 0 else 0.09), when)
        # Offbeat eighths bass, synthwave style.
        for eighth in range(8):
            note = root if eighth % 4 != 3 else root + 12
            place(bass, bass_note(note, BEAT / 2 * 0.9), start + eighth * BEAT / 2)
    elif 12 <= bar < 14:
        # Breakdown: half-time kick and a held bass.
        if bar == 12:
            place(bass, bass_note(root, BAR) * 0.8, start)
        place(drums, K * 0.8, start)

# Lead melody in the drop (bars 14-23), one phrase per four bars, repeated with a variation.
MELODY = [
    (0, 1.5, 76), (1.5, 0.5, 74), (2, 1, 72), (3, 1, 71),
    (4, 1.5, 72), (5.5, 0.5, 74), (6, 2, 76),
    (8, 1, 79), (9, 1, 76), (10, 1, 74), (11, 1, 72),
    (12, 1.5, 74), (13.5, 0.5, 72), (14, 2, 71),
]
for phrase_start in (14, 18):
    for beat, length, note in MELODY:
        variation = 12 if phrase_start == 18 and beat >= 8 else 0
        place(lead, lead_note(note + variation, length * BEAT * 0.95), phrase_start * BAR + beat * BEAT)
for beat, length, note in [(0, 2, 76), (2, 2, 79), (4, 4, 81)]:
    place(lead, lead_note(note, length * BEAT * 0.95), 22 * BAR + beat * BEAT)

# Effects: risers into 8 s and 28 s, impacts on the drops and the final hit at 48 s, glitches on cuts.
place(fx, riser(4.0) * 0.5, 4.0)
place(fx, riser(4.0) * 0.6, 24.0)
for when in (8.0, 28.0, 48.0):
    place(fx, impact() * (1.0 if when != 48.0 else 1.2), when)
for when in (16.0, 20.0, 36.0, 40.0, 44.0):
    place(fx, glitch(), when - 0.25)

# ------------------------------------------------------------------ mix

mix = (
    stereo(drums * 0.9)
    + stereo(bass * 0.55 * sidechain)
    + reverb(delay(stereo(arp * 0.22 * (0.5 + 0.5 * sidechain), -0.2), BEAT * 0.75, 0.4, 0.35), 1.8, 0.25, 2)
    + reverb(stereo(pads * 0.33 * (0.4 + 0.6 * sidechain), 0.15), 3.0, 0.45, 3)
    + reverb(delay(stereo(lead * 0.2), BEAT * 0.5, 0.35, 0.3), 2.5, 0.35, 4)
    + reverb(stereo(fx * 0.5), 2.0, 0.3, 5)
)
mix = mix[:N]
# Fade out the last four seconds.
fade = np.ones(N)
fade_start = int(52 * SR)
fade[fade_start:] = np.linspace(1, 0, N - fade_start) ** 2
mix *= fade[:, None]
mix = highpass(mix.T, 25).T
mix = lowpass(mix.T, 16000).T
mix /= np.max(np.abs(mix))
mix = np.tanh(mix * 1.4)
mix /= np.max(np.abs(mix)) / 0.89

out = sys.argv[1] if len(sys.argv) > 1 else "public/soundtrack.wav"
wavfile.write(out, SR, (mix * 32767).astype(np.int16))
print("wrote", out, f"{LENGTH:.0f}s")
