#!/usr/bin/env python3
"""
Rally Copilot voice pack builder.

Pre-generates the whole closed vocabulary with a neural British male voice
(edge-tts, en-GB-RyanNeural) in two intonation sets:
  normal/  - measured pace for 4/5/6, links, modifiers
  urgent/  - faster, clipped, for hairpin/1/2, brake, cautions

Output: app/src/main/assets/voice/<set>/<key>.mp3 plus manifest.json with
measured durations (the scheduler needs them for end-anchored timing).

Usage: python build.py [output_dir]
"""
import asyncio
import json
import os
import struct
import sys

try:
    import truststore
    truststore.inject_into_ssl()
except ImportError:
    pass

import edge_tts

VOICE = "en-GB-RyanNeural"

# key -> spoken text
VOCAB = {}
for d in ("left", "right"):
    for i, word in enumerate(["one", "two", "three", "four", "five", "six"], 1):
        VOCAB[f"{d}_{word}"] = f"{d} {word}"
    VOCAB[f"{d}_hairpin"] = f"hairpin {d}"
    VOCAB[f"{d}_square"] = f"square {d}"
    VOCAB[f"{d}_kink"] = f"kink {d}"
for k, t in {
    "tightens": "tightens", "opens": "opens", "long": "long", "into": "into",
    "caution": "caution", "brake": "brake", "junction": "junction",
    "crossing": "crossing", "ford": "ford", "cattle_grid": "cattle grid",
    "narrow_bridge": "narrow bridge", "gate": "gate", "level_crossing": "level crossing",
    "finish": "finish", "warn_temps": "temperatures rising, ease off",
    "warn_battery": "battery voltage low",
    "gps_lost": "G P S lost", "gps_ok": "G P S restored",
}.items():
    VOCAB[k] = t
for n in (50, 100, 150, 200, 250, 300, 400, 500, 600, 800, 1000):
    VOCAB[f"d_{n}"] = str(n)
for g in range(1, 7):
    VOCAB[f"gear_{g}"] = ["first", "second", "third", "fourth", "fifth", "sixth"][g - 1]

SETS = {
    "normal": {"rate": "+8%", "pitch": "+0Hz"},
    "urgent": {"rate": "+28%", "pitch": "+18Hz"},
}


def mp3_duration_ms(path):
    """Rough MP3 duration: parse frame headers (CBR assumption is fine for TTS output)."""
    # edge-tts emits 24kHz mono mp3; estimate from bitrate in first frame header.
    with open(path, "rb") as f:
        data = f.read()
    i = 0
    # skip ID3
    if data[:3] == b"ID3":
        size = ((data[6] & 0x7F) << 21) | ((data[7] & 0x7F) << 14) | ((data[8] & 0x7F) << 7) | (data[9] & 0x7F)
        i = 10 + size
    while i < len(data) - 4 and not (data[i] == 0xFF and (data[i + 1] & 0xE0) == 0xE0):
        i += 1
    if i >= len(data) - 4:
        return 0
    bitrates = [0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0]  # MPEG2 L3
    br = bitrates[(data[i + 2] >> 4) & 0x0F] * 1000
    if br == 0:
        return 0
    return int((len(data) - i) * 8 * 1000 / br)


async def synth(key, text, cfg, outdir):
    path = os.path.join(outdir, f"{key}.mp3")
    tts = edge_tts.Communicate(text, VOICE, rate=cfg["rate"], pitch=cfg["pitch"])
    await tts.save(path)
    return key, mp3_duration_ms(path)


async def main():
    out = sys.argv[1] if len(sys.argv) > 1 else "app/src/main/assets/voice"
    manifest = {"voice": VOICE, "sets": {}}
    for set_name, cfg in SETS.items():
        outdir = os.path.join(out, set_name)
        os.makedirs(outdir, exist_ok=True)
        durations = {}
        # small batches to be polite to the service
        keys = list(VOCAB.items())
        for i in range(0, len(keys), 8):
            batch = keys[i:i + 8]
            results = await asyncio.gather(*[synth(k, t, cfg, outdir) for k, t in batch])
            for k, ms in results:
                durations[k] = ms
            print(f"  {set_name}: {min(i + 8, len(keys))}/{len(keys)}")
        manifest["sets"][set_name] = durations
    with open(os.path.join(out, "manifest.json"), "w") as f:
        json.dump(manifest, f, indent=1)
    print(f"wrote {out}/manifest.json")


if __name__ == "__main__":
    asyncio.run(main())
