#!/usr/bin/env python3
"""
Read a black box trace and answer the questions that keep coming up.

Usage:
    python tools/blackbox/analyse.py <drive-N.jsonl> [--csv out.csv]

Prints a drive summary, then works through the recurring suspects:
  - why corners were not called (suppressed / never triggered / not in horizon)
  - why observations did not reach the profile, by reason
  - whether the map view ever had less data than the viewport needed
  - GPS, OBD and IMU health over the drive
"""
import collections
import json
import sys


def load(path):
    rows = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                rows.append(json.loads(line))
            except json.JSONDecodeError:
                pass  # a trace truncated by a battery pull is still worth reading
    return rows


def by_kind(rows):
    d = collections.defaultdict(list)
    for r in rows:
        d[r.get("k", "?")].append(r)
    return d


def fmt_mps(v):
    return "?" if v is None else f"{v * 2.23694:.0f} mph"


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 1
    rows = load(sys.argv[1])
    if not rows:
        print("empty trace")
        return 1
    k = by_kind(rows)
    t0, t1 = rows[0]["t"], rows[-1]["t"]
    mins = (t1 - t0) / 60000.0

    print(f"=== DRIVE: {mins:.1f} min, {len(rows)} records ===")
    for s in k.get("drive_start", []):
        for key, val in s.items():
            if key not in ("t", "k"):
                print(f"  {key}: {val}")
    for p in k.get("profile", []):
        print(f"  profile push={p.get('push')}")
        for band in (p.get("bands") or "").split(";"):
            print(f"    {band}")
    print()

    states = k.get("s", [])
    if states:
        speeds = [s["speed"] for s in states if s.get("speed") is not None]
        moving = [v for v in speeds if v > 2]
        print("=== MOTION ===")
        if speeds:
            print(f"  max {fmt_mps(max(speeds))}, "
                  f"mean while moving {fmt_mps(sum(moving) / len(moving)) if moving else '?'}")
        spirited = sum(1 for s in states if s.get("spirited"))
        pressing = sum(1 for s in states if s.get("pressingOn"))
        quiet = sum(1 for s in states if s.get("quiet"))
        print(f"  spirited {100 * spirited / len(states):.0f}% of ticks, "
              f"pressing on {100 * pressing / len(states):.0f}%, "
              f"QUIET (no calls) {100 * quiet / len(states):.0f}%")
        rpms = [s["rpm"] for s in states if s.get("rpm")]
        if rpms:
            print(f"  rpm {min(rpms)}-{max(rpms)}, OBD speed present on "
                  f"{100 * sum(1 for s in states if s.get('obdSpeed') is not None) / len(states):.0f}% of ticks")
        else:
            print("  no RPM seen at all — OBD never answered")
        alat = [s["aLatImu"] for s in states if s.get("aLatImu") is not None]
        if alat:
            print(f"  IMU lateral: peak {max(alat) / 9.81:.2f} g "
                  f"(available on {100 * len(alat) / len(states):.0f}% of ticks)")
        else:
            print("  IMU lateral never available — mount alignment never completed")
        imu = k.get("imu", [])
        wob = [r["wobbleDeg"] for r in imu if r.get("wobbleDeg") is not None]
        if wob:
            w = sorted(wob)
            print(f"  mount wobble: median {w[len(w) // 2]:.1f} deg, p90 {w[int(len(w) * .9)]:.1f} deg "
                  f"(rigid mount ~2-5; above 8 the phone is moving in its holder "
                  f"and alignment/camber/audit are all disabled)")
        nohorizon = sum(1 for s in states if not s.get("horizonCorners"))
        print(f"  ticks with an EMPTY horizon: {100 * nohorizon / len(states):.0f}%")
        print()

    imu2 = k.get("imu", [])
    verd = [r.get("slipVerdict") for r in imu2 if r.get("slipVerdict")]
    if verd:
        print("=== IS THE CAR GOING WHERE IT IS POINTING? ===")
        counts = collections.Counter(verd)
        for v, n in counts.most_common():
            print(f"  {v:11} {n:6d} samples ({100 * n / len(verd):4.1f}%)")
        sliding = [r for r in imu2 if r.get("sliding")]
        print(f"  sliding on {len(sliding)} samples ({100 * len(sliding) / len(imu2):.1f}%)")
        ratios = sorted(r["slipRatio"] for r in imu2
                        if r.get("slipRatio") and r.get("slipVerdict") not in (None, "UNKNOWN"))
        if ratios:
            print(f"  yaw/course ratio while cornering: p10 {ratios[len(ratios) // 10]:.2f} "
                  f"median {ratios[len(ratios) // 2]:.2f} p90 {ratios[int(len(ratios) * .9)]:.2f} "
                  f"(1.0 = gripping)")
        dr = [r["drivenR"] for r in imu2 if r.get("drivenR")]
        if dr:
            d = sorted(dr)
            print(f"  driven radius measured on {len(dr)} samples, median {d[len(d) // 2]:.0f} m")
        print()

    print("=== WHAT WAS SAID ===")
    notes = k.get("note", [])
    print(f"  {len(notes)} calls")
    for n in notes[:40]:
        print(f"    +{(n['t'] - t0) / 1000:6.1f}s  {n.get('keys'):45} "
              f"chain={n.get('chain')} at {fmt_mps(n.get('speed'))}")
    if len(notes) > 40:
        print(f"    ... {len(notes) - 40} more")
    print()

    print("=== WHY CORNERS WERE NOT CALLED ===")
    sup = k.get("note_suppressed", [])
    if sup:
        print(f"  {len(sup)} suppressed for low path confidence:")
        for s in sup[:12]:
            print(f"    corner {s.get('corner')} {s.get('band')} "
                  f"pathConf={s.get('pathConf')} < {s.get('need')}")
    pend = k.get("note_pending", [])
    if pend:
        worst = collections.Counter()
        for p in pend:
            worst[p.get("corner")] += 1
        print(f"  {len(pend)} 'nearly called' samples over {len(worst)} corners "
              f"(waiting for the trigger)")
    if not sup and not pend:
        print("  nothing suppressed or pending")
    print()

    print("=== LEARNING ===")
    obs = k.get("obs", [])
    end = k.get("drive_end", [{}])[-1]
    print(f"  {end.get('observations', len(obs))} corners observed, "
          f"{end.get('usable', '?')} usable for the profile")
    reasons = collections.Counter(
        (o.get("rejectedBecause") or "KEPT") for o in obs)
    for reason, n in reasons.most_common():
        print(f"    {n:4d}  {reason}")
    if obs:
        print("  detail:")
        for o in obs[:25]:
            print(f"    corner {o.get('corner')} {o.get('band'):8} r={o.get('rM')}m "
                  f"vMin={fmt_mps(o.get('vMin'))} {o.get('g', 0):.2f}g "
                  f"mapConf={o.get('mapConf')} pathConf={o.get('pathConf')} "
                  f"-> {o.get('rejectedBecause') or 'KEPT'}")
    print()

    print("=== MAP VIEW ===")
    mf = k.get("map_fetch", [])
    if mf:
        counts = [m.get("edges", 0) for m in mf]
        print(f"  {len(mf)} fetches, {min(counts)}-{max(counts)} edges "
              f"(mean {sum(counts) / len(counts):.0f}), "
              f"slowest {max(m.get('ms', 0) for m in mf)} ms")
        thin = [m for m in mf if m.get("edges", 0) < 5]
        if thin:
            print(f"  !! {len(thin)} fetches returned under 5 roads — "
                  f"the map would look empty here:")
            for m in thin[:10]:
                print(f"     +{(m['t'] - t0) / 1000:6.1f}s at {m.get('lat')},{m.get('lon')} "
                      f"edge={m.get('edge')} -> {m.get('edges')} roads")
    else:
        print("  no map fetches recorded (drive screen not open?)")
    print()

    print("=== HEALTH ===")
    print(f"  fixes: {len(k.get('fix', []))}, rejected: {len(k.get('fix_rejected', []))}, "
          f"match lost: {len(k.get('match_lost', []))}, "
          f"horizon unbuildable: {len(k.get('horizon_none', []))}")
    for r in k.get("fix_rejected", [])[:5]:
        print(f"    rejected: {r.get('why')} {r.get('acc') or r.get('mps')}")
    for a in k.get("audio_cal", []):
        print(f"  audio calibration: {a.get('ms')} ms {a.get('why') or ''} "
              f"(noise {a.get('noise')})")

    if "--csv" in sys.argv:
        out = sys.argv[sys.argv.index("--csv") + 1]
        keys = sorted({key for s in states for key in s})
        with open(out, "w", encoding="utf-8") as f:
            f.write(",".join(keys) + "\n")
            for s in states:
                f.write(",".join(str(s.get(key, "")) for key in keys) + "\n")
        print(f"\nwrote {out} ({len(states)} rows) for plotting")
    return 0


if __name__ == "__main__":
    sys.exit(main())
