#!/usr/bin/env python3
"""
Corner-detection regression tests.

Synthetic roads whose true radius is known by construction: straight -> constant
radius arc -> straight, tangent-continuous, digitised at a realistic node spacing.
The detector must recover the radius it was given.

This exists because the v0.11 detector did not. It measured curvature at the 5 m
resample step, which is finer than OSM's real node spacing, so three consecutive
points either sat on one chord (collinear, infinite radius) or straddled a vertex
where the whole turn angle piled into one triangle. A true 200 m sweeper came back
as a comb of hairpin spikes, and because severity was taken from the MINIMUM radius
in a run, the artefact set the band. 5,918 "hairpins" shipped in the Stroud region,
79% of them on residential estate roads.

Run: python test_corners.py
"""
import importlib.util
import math
import os
import sys

_here = os.path.dirname(os.path.abspath(__file__))
_spec = importlib.util.spec_from_file_location("mb", os.path.join(_here, "build.py"))
mb = importlib.util.module_from_spec(_spec)
sys.argv = [sys.argv[0]]
_spec.loader.exec_module(mb)

EARTH_R = 6371000.0
BANDS = [(12, "HAIRPIN"), (25, "ONE"), (40, "TWO"), (70, "THREE"),
         (120, "FOUR"), (200, "FIVE"), (400, "SIX")]


def band(r):
    for upper, name in BANDS:
        if r < upper:
            return name
    return "FLAT"


def to_latlon(pts, lat0=51.75, lon0=-2.22):
    cos0 = math.cos(math.radians(lat0))
    return [(lat0 + math.degrees(y / EARTH_R),
             lon0 + math.degrees(x / (EARTH_R * cos0))) for x, y in pts]


def arc_road(radius_m, sweep_deg, node_m, lead_m=80):
    """Straight, then a constant-radius LEFT arc, then straight. Tangent-continuous:
    any kink at the joins would be a real corner and would corrupt the fixture."""
    pts = [(-lead_m, 0.0)]
    x = -lead_m + node_m
    while x < 0:
        pts.append((x, 0.0))
        x += node_m
    pts.append((0.0, 0.0))
    total = math.radians(sweep_deg) * radius_m
    d = node_m
    while d < total:
        th = d / radius_m
        pts.append((radius_m * math.sin(th), radius_m * (1 - math.cos(th))))
        d += node_m
    th = math.radians(sweep_deg)
    ex, ey = radius_m * math.sin(th), radius_m * (1 - math.cos(th))
    tx, ty = math.cos(th), math.sin(th)
    d = node_m
    while d <= lead_m:
        pts.append((ex + tx * d, ey + ty * d))
        d += node_m
    return pts


def detect(pts_xy):
    """The full build pipeline for one edge: smooth, resample, extract."""
    latlons = to_latlon(pts_xy)
    lat0, lon0 = latlons[0]
    xy = [mb.to_xy(la, lo, lat0, lon0) for la, lo in latlons]
    sm = mb.chaikin(xy)
    cos0 = math.cos(math.radians(lat0))
    sm_ll = [(lat0 + math.degrees(y / mb.EARTH_R),
              lon0 + math.degrees(x / (mb.EARTH_R * cos0))) for x, y in sm]
    return mb.extract_corners(mb.resample(sm_ll), source_node_count=len(latlons))


# (true radius m, sweep deg, node spacing m, tolerance %)
CASES = [
    (8, 200, 3, 12), (12, 180, 4, 12), (15, 150, 5, 12), (20, 140, 6, 12),
    (25, 120, 8, 10), (40, 100, 10, 10), (60, 90, 12, 10), (90, 80, 15, 10),
    (120, 70, 20, 10), (200, 60, 25, 10),
    (200, 60, 60, 15),   # coarsely digitised rural lane
    (300, 50, 30, 10),
]

failures = []


def check(name, cond, detail=""):
    print(f"  {'PASS' if cond else 'FAIL'}  {name}{(' — ' + detail) if detail else ''}")
    if not cond:
        failures.append(name)


print("radius recovery (true -> detected):")
for radius, sweep, node, tol in CASES:
    corners = detect(arc_road(radius, sweep, node))
    if not corners:
        check(f"R={radius}m nodes/{node}m", False, "no corner detected")
        continue
    main = max(corners, key=lambda c: c["arc"])
    err = 100 * (main["min_r"] - radius) / radius
    check(f"R={radius:3d}m nodes/{node:2d}m", abs(err) <= tol,
          f"got {main['min_r']:.1f}m ({band(main['min_r'])}), {err:+.0f}%")
    # One arc must not fragment into a burst of calls.
    check(f"R={radius:3d}m nodes/{node:2d}m produces ONE corner", len(corners) == 1,
          f"got {len(corners)}")

print("\nstraights stay silent:")
check("400 m dead straight", detect([(x, 0.0) for x in range(0, 420, 20)]) == [])
check("straight with 1 m of digitisation wobble",
      detect([(x, 0.4 if (x // 20) % 2 else -0.4) for x in range(0, 420, 20)]) == [])

print("\nseverity bands land where they should:")
for radius, expected in [(10, "HAIRPIN"), (18, "ONE"), (32, "TWO"),
                         (55, "THREE"), (95, "FOUR"), (160, "FIVE")]:
    corners = detect(arc_road(radius, 110, max(3, int(radius / 6))))
    got = band(max(corners, key=lambda c: c["arc"])["min_r"]) if corners else "NONE"
    check(f"R={radius}m -> {expected}", got == expected, f"got {got}")

print()
if failures:
    print(f"{len(failures)} FAILURE(S): {', '.join(failures)}")
    sys.exit(1)
print("all corner-detection tests passed")
