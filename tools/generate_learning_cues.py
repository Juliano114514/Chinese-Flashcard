"""Generate original, soft learning cues; no external audio or runtime synthesis."""
import math
import struct
import wave
from pathlib import Path

OUTPUT = Path(__file__).resolve().parents[1] / "feature/study/src/main/res/raw"
RATE = 24000


def render(name, duration, notes):
    samples = []
    for i in range(round(duration * RATE)):
        t = i / RATE
        value = 0.0
        for start, frequency, length, gain in notes:
            age = t - start
            if 0 <= age < length:
                # Raised-cosine attack/release avoids clicks and sharp transients.
                attack = (1 - math.cos(math.pi * min(age / .025, 1))) / 2
                release = (1 - math.cos(math.pi * min((length - age) / .09, 1))) / 2
                value += gain * attack * release * math.exp(-3 * age) * math.sin(2 * math.pi * frequency * age)
        samples.append(round(max(-.28, min(.28, value)) * 32767))
    with wave.open(str(OUTPUT / name), "wb") as audio:
        audio.setparams((1, 2, RATE, 0, "NONE", "not compressed"))
        audio.writeframes(struct.pack('<' + 'h' * len(samples), *samples))


if __name__ == '__main__':
    render('gentle_correct.wav', .40, [(0, 523.25, .25, .16), (.12, 659.25, .28, .14)])
    render('gentle_retry.wav', .32, [(0, 349.23, .22, .12), (.10, 329.63, .22, .10)])
    render('gentle_complete.wav', .72, [(0, 523.25, .30, .14), (.12, 659.25, .30, .12),
                                       (.24, 783.99, .36, .12), (.36, 1046.50, .36, .08)])
