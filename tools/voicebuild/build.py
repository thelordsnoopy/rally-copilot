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
import re
import struct
import subprocess
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
    "off_camber": "off camber",
    "care": "care",
    "caution": "caution", "brake": "brake", "junction": "junction",
    "crossing": "crossing", "ford": "ford", "cattle_grid": "cattle grid",
    "narrow_bridge": "narrow bridge", "gate": "gate", "level_crossing": "level crossing",
    "speed_camera": "camera", "average_camera": "average speed check",
    "crest": "crest",
    "coach_good": "good through there",
    "coach_more": "you had more there",
    "coach_hot": "bit hot into that one",
    "finish": "finish", "warn_temps": "temperatures rising, ease off",
    "warn_battery": "battery voltage low",
    "warn_ice": "caution, possible ice",
    "gps_lost": "G P S lost", "gps_ok": "G P S restored",
}.items():
    VOCAB[k] = t
for n in (50, 100, 150, 200, 250, 300, 400, 500, 600, 800, 1000):
    VOCAB[f"d_{n}"] = str(n)
for g in range(1, 7):
    VOCAB[f"gear_{g}"] = ["first", "second", "third", "fourth", "fifth", "sixth"][g - 1]
# Target speed in mph, 5 mph steps. Spoken AFTER the corner call ("left four, forty"),
# where link distances are always spoken BEFORE it — position disambiguates the two.
for n in range(20, 105, 5):
    VOCAB[f"s_{n}"] = str(n)

# Urgency is carried by PACE alone. Pitch-shifting the same voice up sounds
# artificial — a real co-driver gets faster and clipped under pressure, not squeaky.
SETS = {
    "normal": {"rate": "+8%", "pitch": "+0Hz"},
    "urgent": {"rate": "+30%", "pitch": "+0Hz"},
}


def _ffprobe_duration_ms(path):
    """Exact duration via ffprobe, or None if ffprobe isn't available."""
    try:
        out = subprocess.run(
            ["ffprobe", "-v", "error", "-show_entries", "format=duration",
             "-of", "csv=p=0", path],
            capture_output=True, text=True, timeout=30,
        )
        return int(round(float(out.stdout.strip()) * 1000)) if out.returncode == 0 else None
    except Exception:
        return None


def trim_silence(path, head_ms=20, tail_ms=60):
    """
    Strip edge-tts's leading/trailing silence.

    Neural TTS pads every utterance with ~1 s of dead air — on a clip like
    "left four" that is 0.62 s of speech inside a 1.66 s file. Concatenated into
    a five-word note it becomes ~5 s of silence, which is the difference between
    a co-driver who calls a corner in time and one who is still talking as you
    turn in. Idempotent: a trimmed clip has no silence left to find.
    """
    try:
        probe = subprocess.run(
            ["ffmpeg", "-v", "info", "-i", path,
             "-af", "silencedetect=noise=-40dB:d=0.04", "-f", "null", "-"],
            capture_output=True, text=True, timeout=60,
        )
    except Exception:
        return None
    log = probe.stderr
    total = _ffprobe_duration_ms(path)
    if total is None:
        return None

    starts = [float(m) for m in re.findall(r"silence_start: ([0-9.]+)", log)]
    ends = [float(m) for m in re.findall(r"silence_end: ([0-9.]+)", log)]
    total_s = total / 1000.0
    # Leading silence only counts if it starts at the very beginning of the file.
    lead_s = ends[0] if starts and ends and abs(starts[0]) < 0.01 else 0.0
    # Trailing silence: the final silence period either has no matching end (ffmpeg
    # ran out of file) or ends flush with the end of the file.
    tail_s = total_s
    if starts and starts[-1] > lead_s + 0.01:
        unterminated = len(starts) > len(ends)
        runs_to_end = bool(ends) and abs(ends[-1] - total_s) < 0.05
        if unterminated or runs_to_end:
            tail_s = starts[-1]

    begin = max(0.0, lead_s - head_ms / 1000.0)
    end = min(total / 1000.0, tail_s + tail_ms / 1000.0)
    if end - begin < 0.10 or (begin < 0.005 and end > total / 1000.0 - 0.005):
        return total  # nothing worth trimming

    tmp = path + ".trim.mp3"
    try:
        r = subprocess.run(
            ["ffmpeg", "-v", "error", "-y", "-i", path,
             "-ss", f"{begin:.3f}", "-to", f"{end:.3f}",
             "-c:a", "libmp3lame", "-b:a", "48k", "-ar", "24000", "-ac", "1", tmp],
            capture_output=True, text=True, timeout=60,
        )
        if r.returncode != 0 or not os.path.exists(tmp):
            return total
        os.replace(tmp, path)
        return _ffprobe_duration_ms(path) or total
    except Exception:
        if os.path.exists(tmp):
            os.remove(tmp)
        return total


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
    ms = trim_silence(path)
    return key, (ms if ms else mp3_duration_ms(path))


async def main():
    out = sys.argv[1] if len(sys.argv) > 1 else "app/src/main/assets/voice"
    force = "--force" in sys.argv
    # Incremental by default: only synthesise clips that are missing, and merge into
    # the existing manifest. Re-rendering the whole vocabulary to add a few words
    # would reshuffle every measured duration the note scheduler depends on.
    manifest = {"voice": VOICE, "sets": {}}
    manifest_path = os.path.join(out, "manifest.json")
    if os.path.exists(manifest_path) and not force:
        with open(manifest_path) as f:
            manifest = json.load(f)
        manifest.setdefault("sets", {})

    for set_name, cfg in SETS.items():
        outdir = os.path.join(out, set_name)
        os.makedirs(outdir, exist_ok=True)
        durations = manifest["sets"].setdefault(set_name, {})

        if "--retrim" in sys.argv:
            # Re-trim clips already on disk (no re-synthesis, no network).
            for k in VOCAB:
                p = os.path.join(outdir, f"{k}.mp3")
                if os.path.exists(p):
                    ms = trim_silence(p)
                    if ms:
                        durations[k] = ms
            print(f"  {set_name}: re-trimmed {len(durations)} clips")
            continue

        todo = [
            (k, t) for k, t in VOCAB.items()
            if force or not os.path.exists(os.path.join(outdir, f"{k}.mp3"))
            or k not in durations
        ]
        if not todo:
            print(f"  {set_name}: up to date ({len(VOCAB)} clips)")
            continue
        # small batches to be polite to the service
        for i in range(0, len(todo), 8):
            batch = todo[i:i + 8]
            results = await asyncio.gather(*[synth(k, t, cfg, outdir) for k, t in batch])
            for k, ms in results:
                durations[k] = ms
            print(f"  {set_name}: {min(i + 8, len(todo))}/{len(todo)} new")

    with open(manifest_path, "w") as f:
        json.dump(manifest, f, indent=1)
    print(f"wrote {manifest_path}")


if __name__ == "__main__":
    asyncio.run(main())
