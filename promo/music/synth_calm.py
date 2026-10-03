"""Calm soundtrack for the long promo cut (v3), synthesized from scratch with numpy/scipy.

Same idea as synth.py (no samples, no third-party recordings, no third-party license), but slower and softer:
75 BPM in A minor, 37 bars of 3.2 seconds (118.4 seconds), structured to match the video's scenes:

  bars  0-3   intro: pad swell and a filtered arpeggio, a soft bloom at 12.8 s
  bars  4-19  groove: soft half-time kick, brushed hats, sub bass, open arpeggio
  bars 20-33  the same groove with a bell melody on top
  bars 34-36  outro: one long chord that fades out

Usage: python3 music/synth_calm.py [out.wav]
"""
import sys

import numpy as np
from scipy import signal
from scipy.io import wavfile

SR = 48_000
BPM = 75
BEAT = 60 / BPM
BAR = 4 * BEAT
BARS = 37
LENGTH = BARS * BAR
N = int(LENGTH * SR)
rng = np.random.default_rng(1984)

# Scene changes in the video (bar numbers); a soft swell leads into each.
SCENE_BARS = [4, 9, 13, 16, 20, 26, 28, 31, 34]


def t_axis(seconds):
    return np.arange(int(seconds * SR)) / SR


def midi(note):
    return 440.0 * 2 ** ((note - 69) / 12)


def lowpass(x, cutoff, order=2):
    b, a = signal.butter(order, min(cutoff, SR * 0.45) / (SR / 2), "low")
    return signal.lfilter(b, a, x)


def highpass(x, cutoff, order=2):
    b, a = signal.butter(order, cutoff / (SR / 2), "high")
    return signal.lfilter(b, a, x)


def bandpass(x, lo, hi, order=2):
    b, a = signal.butter(order, [lo / (SR / 2), hi / (SR / 2)], "band")
    return signal.lfilter(b, a, x)


def place(track, sound, start):
    i = int(start * SR)
    if i >= len(track):
        return
    end = min(len(track), i + len(sound))
    track[i:end] += sound[: end - i]


def stereo(mono, pan=0.0):
    return np.stack([mono * np.sqrt((1 - pan) / 2), mono * np.sqrt((1 + pan) / 2)], axis=1)


def reverb(x, seconds=3.0, mix=0.35, seed=1):
    """Convolution with decaying stereo noise; x is (n, 2)."""
    r = np.random.default_rng(seed)
    n = int(seconds * SR)
    decay = np.exp(-np.arange(n) / SR * 6.5 / seconds)
    out = np.zeros_like(x)
    for ch in range(2):
        ir = lowpass(r.standard_normal(n) * decay, 5000)
        ir /= np.sqrt(np.sum(ir**2))
        out[:, ch] = signal.fftconvolve(x[:, ch], ir)[: len(x)]
    return x * (1 - mix) + out * mix


def delay(x, seconds, feedback=0.4, mix=0.3):
    d = int(seconds * SR)
    out = x.copy()
    for i in range(1, 6):
        shifted = np.zeros_like(x)
        shifted[d * i :] = x[: len(x) - d * i] * feedback**i
        if i % 2 == 1:
            shifted = shifted[:, ::-1]
        out += shifted * mix
    return out


# ------------------------------------------------------------------ instruments

def soft_kick():
    t = t_axis(0.6)
    freq = 42 + 60 * np.exp(-t * 22)
    body = np.sin(2 * np.pi * np.cumsum(freq) / SR) * np.exp(-t * 6)
    return np.tanh(1.2 * body)


def brush(open_=False):
    t = t_axis(0.3 if open_ else 0.09)
    noise = bandpass(rng.standard_normal(len(t)), 5000, 14000)
    return noise * np.exp(-t * (10 if open_ else 45))


def rim():
    t = t_axis(0.15)
    tone = np.sin(2 * np.pi * 820 * t) * np.exp(-t * 60)
    noise = bandpass(rng.standard_normal(len(t)), 1500, 6000) * np.exp(-t * 50)
    return 0.5 * tone + 0.4 * noise


def sub_bass(note, seconds):
    f = midi(note)
    t = t_axis(seconds)
    x = np.sin(2 * np.pi * f * t) + 0.25 * lowpass(signal.sawtooth(2 * np.pi * f * t), 400)
    e = np.clip(t / 0.08, 0, 1) * np.clip((seconds - t) / 0.3, 0, 1)
    return np.tanh(1.3 * x) * e


def pluck(note, seconds=0.6, bright=1.0):
    f = midi(note)
    t = t_axis(seconds)
    x = signal.square(2 * np.pi * f * t, duty=0.4) * 0.6 + signal.sawtooth(2 * np.pi * f * 1.003 * t) * 0.4
    return lowpass(x, 600 + 2200 * bright) * np.exp(-t * 6) * np.clip(t / 0.004, 0, 1)


def pad_chord(notes, seconds, attack=1.4, release=1.2, cutoff=1600):
    t = t_axis(seconds)
    x = np.zeros(len(t))
    for note in notes:
        f = midi(note)
        for detune in (-0.1, -0.04, 0, 0.04, 0.1):
            x += signal.sawtooth(2 * np.pi * f * 2 ** (detune / 12) * t + rng.uniform(0, 2 * np.pi))
    x = lowpass(x / (len(notes) * 5), cutoff)
    return x * np.clip(t / attack, 0, 1) * np.clip((seconds - t) / release, 0, 1)


def bell(note, seconds):
    """FM bell for the melody: a soft attack and a long, gently decaying tail."""
    f = midi(note)
    t = t_axis(seconds)
    mod = np.sin(2 * np.pi * f * 2 * t) * 1.6 * np.exp(-t * 2.5)
    x = np.sin(2 * np.pi * f * t + mod) + 0.3 * np.sin(2 * np.pi * f * 2 * t)
    return x * np.exp(-t * 1.4) * np.clip(t / 0.01, 0, 1) * np.clip((seconds - t) / 0.2, 0, 1)


def swell(seconds=2.4):
    """Reverse-cymbal style noise that rises softly into a scene change."""
    t = t_axis(seconds)
    noise = lowpass(highpass(rng.standard_normal(len(t)), 2500), 9000)
    return noise * (t / seconds) ** 3 * 0.2


def bloom():
    """A soft low boom with shimmer, used once when the logo appears."""
    t = t_axis(5.0)
    boom = np.sin(2 * np.pi * np.cumsum(38 + 30 * np.exp(-t * 4)) / SR) * np.exp(-t * 1.0)
    shimmer = highpass(rng.standard_normal(len(t)), 6000) * np.exp(-t * 1.2) * 0.12
    return np.tanh(1.2 * boom) + shimmer


# ------------------------------------------------------------------ arrangement

PROGRESSION = [  # Am, F, C, G (bass root, chord tones)
    (45, [57, 60, 64]),
    (41, [53, 57, 60]),
    (48, [55, 60, 64]),
    (43, [55, 59, 62]),
]

drums = np.zeros(N)
bass = np.zeros(N)
arp = np.zeros(N)
pads = np.zeros(N)
melody = np.zeros(N)
fx = np.zeros(N)
sidechain = np.ones(N)

K, H, HO, R = soft_kick(), brush(), brush(True), rim()
duck = 1 - 0.35 * np.exp(-np.arange(int(0.5 * SR)) / SR * 8)

for bar in range(BARS):
    start = bar * BAR
    root, chord = PROGRESSION[bar % 4]
    groove = 4 <= bar < 34

    if bar < 34:
        place(pads, pad_chord(chord + [chord[0] + 12], BAR + 1.2), start)
    elif bar == 34:
        place(pads, pad_chord([45, 57, 60, 64, 69, 76], 3 * BAR, attack=0.8, release=4.0) * 1.3, start)

    # Arpeggio in eighths; it opens up over the intro and stays mellow.
    if bar < 34:
        pattern = [0, 2, 1, 3, 2, 1, 3, 2]
        tones = chord + [chord[0] + 12]
        bright = 0.1 + 0.5 * min(1, bar / 4)
        for i, p in enumerate(pattern):
            place(arp, pluck(tones[p] + 12, 0.7, bright) * (1.0 if i % 2 == 0 else 0.7), start + i * BEAT / 2)

    if groove:
        place(bass, sub_bass(root, BAR * 0.98), start)
        for beat in range(4):
            when = start + beat * BEAT
            if beat in (0, 2):
                place(drums, K, when)
                i = int(when * SR)
                end = min(N, i + len(duck))
                sidechain[i:end] = np.minimum(sidechain[i:end], duck[: end - i])
            if beat == 2:
                place(drums, R * 0.5, when)
            place(drums, H * 0.08, when)
            place(drums, (HO if beat == 3 else H) * 0.06, when + BEAT / 2)

# Bell melody over the chat, the phone and the montage (bars 20-33): a slow eight-bar phrase, repeated higher.
PHRASE = [
    (0, 2, 76), (2, 2, 72), (4, 4, 74),
    (8, 2, 72), (10, 2, 69), (12, 4, 71),
    (16, 2, 72), (18, 2, 76), (20, 4, 79),
    (24, 2, 76), (26, 2, 74), (28, 4, 72),
]
for phrase_start, shift in ((20, 0), (28, 0)):
    for beat, length, note in PHRASE:
        when = phrase_start * BAR + beat * BEAT
        if when < 34 * BAR:
            place(melody, bell(note + shift, length * BEAT + 1.5), when)
# A last bell over the end card.
place(melody, bell(81, 6.0) * 0.8, 34 * BAR)

place(fx, bloom() * 0.9, 4 * BAR)
for bar in SCENE_BARS:
    place(fx, swell(), bar * BAR - 2.4)

# ------------------------------------------------------------------ mix

mix = (
    stereo(drums * 0.7)
    + stereo(bass * 0.5 * sidechain)
    + reverb(delay(stereo(arp * 0.18, -0.25), BEAT * 0.75, 0.45, 0.35), 2.8, 0.4, 2)
    + reverb(stereo(pads * 0.36 * (0.6 + 0.4 * sidechain), 0.1), 4.0, 0.5, 3)
    + reverb(delay(stereo(melody * 0.16, 0.2), BEAT, 0.4, 0.3), 3.5, 0.45, 4)
    + reverb(stereo(fx * 0.45), 3.0, 0.4, 5)
)
mix = mix[:N]
# Fade in over the first two seconds and out over the last six.
fade = np.ones(N)
fade[: 2 * SR] = np.linspace(0, 1, 2 * SR)
fade_start = int((LENGTH - 6) * SR)
fade[fade_start:] = np.linspace(1, 0, N - fade_start) ** 2
mix *= fade[:, None]
mix = highpass(mix.T, 25).T
mix = lowpass(mix.T, 15000).T
mix /= np.max(np.abs(mix))
mix = np.tanh(mix * 0.9)
mix /= np.max(np.abs(mix)) / 0.6

out = sys.argv[1] if len(sys.argv) > 1 else "public/soundtrack-calm.wav"
wavfile.write(out, SR, (mix * 32767).astype(np.int16))
print("wrote", out, f"{LENGTH:.1f}s")
