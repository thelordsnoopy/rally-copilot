#!/usr/bin/env python3
"""
Rally Copilot map builder.

OSM .pbf  ->  region SQLite the app reads directly.

Pipeline per edge (spec DESIGN.md section 2.2):
  smooth (Chaikin x2, endpoint-preserving)  ->  resample ~5 m  ->  curvature via
  circumradius  ->  segment into corners  ->  confidence from source node density.

Usage:
  python build.py <input.osm.pbf> <output.sqlite> [minlon minlat maxlon maxlat]
"""
import io
import math
import os
import sqlite3
import struct
import sys
import time
import urllib.request
from collections import defaultdict

import osmium
from PIL import Image

DRIVABLE = {
    "motorway", "trunk", "primary", "secondary", "tertiary", "unclassified",
    "residential", "motorway_link", "trunk_link", "primary_link",
    "secondary_link", "tertiary_link",
}

HAZARD_NODE_TAGS = [
    # (key, value or None=any, hazard kind)
    # Cameras first: they win over a co-located crossing tag.
    ("highway", "speed_camera", "SPEED_CAMERA"),
    ("enforcement", "maxspeed", "SPEED_CAMERA"),
    ("enforcement", "average_speed", "AVERAGE_CAMERA"),
    ("man_made", "surveillance", None),          # only when it enforces speed; see node()
    ("ford", None, "FORD"),
    ("barrier", "cattle_grid", "CATTLE_GRID"),
    ("barrier", "gate", "GATE"),
    ("railway", "level_crossing", "LEVEL_CROSSING"),
    ("highway", "crossing", "CROSSING"),
]

# Every way OSM uses to mark a speed camera, across the tagging schemes that have
# been in use over the years — mappers are not consistent and a missed camera is
# the one hazard the driver actually pays for.
def camera_kind(tags):
    """Return SPEED_CAMERA / AVERAGE_CAMERA / None for a node's tag dict."""
    get = tags.get
    hw = get("highway")
    if hw == "speed_camera":
        # Average-speed cameras are sometimes only distinguishable by these.
        if get("enforcement") == "average_speed" or get("speed_camera") == "section" \
                or get("speed_camera:type") in ("section", "average"):
            return "AVERAGE_CAMERA"
        return "SPEED_CAMERA"
    enf = get("enforcement")
    if enf in ("maxspeed", "speed"):
        return "SPEED_CAMERA"
    if enf == "average_speed":
        return "AVERAGE_CAMERA"
    if get("man_made") == "surveillance" and get("surveillance:type") in ("camera", "ALPR"):
        # Only count surveillance that is explicitly traffic/speed enforcement.
        if get("surveillance:zone") in ("traffic", "road") or get("camera:type") == "fixed":
            if enf or get("maxspeed"):
                return "SPEED_CAMERA"
    if get("device") == "speed_camera" or get("speed_camera") is not None:
        return "SPEED_CAMERA"
    return None

CAMERA_CLUSTER_M = 150.0    # cameras closer than this are one enforcement site

# ---- terrain ----------------------------------------------------------------
# Braking distance depends on which way the hill runs: gravity helps you stop
# going up and fights you going down, and downhill-into-a-tightening-corner is
# exactly where road drivers get caught out. Elevation is sampled along every
# road at build time, so the app ships a grade per corner rather than a hundred
# megabytes of terrain.
TERRAIN_ZOOM = 12                  # ~25 m per pixel at this latitude
TERRAIN_URL = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png"
GRADE_WINDOW_M = 80.0              # how far back to measure a corner's approach
# 8%: a genuine brow that hides the road, not every undulation. At 5% the Stroud
# valleys alone produced 245 crests in a few square miles, which is chatter.
CREST_MIN_GRADE_CHANGE = 0.08
CREST_WINDOW_M = 60.0              # measured either side of the summit
RESAMPLE_M = 5.0
STRAIGHT_RADIUS_M = 500.0   # above this, treat as straight
MIN_CORNER_POINTS = 2
MERGE_GAP_M = 15.0          # merge same-sign corner runs separated by less than this
EARTH_R = 6_371_000.0


def to_xy(lat, lon, lat0, lon0):
    x = math.radians(lon - lon0) * EARTH_R * math.cos(math.radians(lat0))
    y = math.radians(lat - lat0) * EARTH_R
    return x, y


def haversine(a, b):
    dlat = math.radians(b[0] - a[0])
    dlon = math.radians(b[1] - a[1])
    s = (math.sin(dlat / 2) ** 2 +
         math.cos(math.radians(a[0])) * math.cos(math.radians(b[0])) * math.sin(dlon / 2) ** 2)
    return 2 * EARTH_R * math.asin(min(1.0, math.sqrt(s)))


def chaikin(points, iterations=2):
    """Endpoint-preserving corner-cutting smooth."""
    for _ in range(iterations):
        if len(points) < 3:
            return points
        out = [points[0]]
        for i in range(len(points) - 1):
            ax, ay = points[i]
            bx, by = points[i + 1]
            out.append((0.75 * ax + 0.25 * bx, 0.75 * ay + 0.25 * by))
            out.append((0.25 * ax + 0.75 * bx, 0.25 * ay + 0.75 * by))
        out.append(points[-1])
        points = out
    return points


def resample(latlons, step=RESAMPLE_M):
    """Uniform arc-length resample of a lat/lon polyline."""
    if len(latlons) < 2:
        return latlons
    cum = [0.0]
    for i in range(1, len(latlons)):
        cum.append(cum[-1] + haversine(latlons[i - 1], latlons[i]))
    total = cum[-1]
    if total < step:
        return [latlons[0], latlons[-1]]
    n = max(2, int(total / step) + 1)
    out = []
    j = 0
    for k in range(n + 1):
        d = min(total, k * total / n)
        while j < len(cum) - 2 and cum[j + 1] < d:
            j += 1
        seg = cum[j + 1] - cum[j]
        t = 0.0 if seg == 0 else (d - cum[j]) / seg
        a, b = latlons[j], latlons[j + 1]
        out.append((a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t))
    return out


def circumradius(a, b, c):
    ab = math.hypot(b[0] - a[0], b[1] - a[1])
    bc = math.hypot(c[0] - b[0], c[1] - b[1])
    ca = math.hypot(a[0] - c[0], a[1] - c[1])
    cross = (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0])
    if abs(cross) < 1e-9:
        return float("inf"), 0
    r = (ab * bc * ca) / (2.0 * abs(cross))
    return r, (1 if cross > 0 else -1)


def extract_corners(latlons, source_node_count):
    """Return corner dicts for one edge geometry (already smoothed+resampled)."""
    if len(latlons) < 3:
        return []
    lat0, lon0 = latlons[0]
    xy = [to_xy(la, lo, lat0, lon0) for la, lo in latlons]
    cum = [0.0]
    for i in range(1, len(latlons)):
        cum.append(cum[-1] + haversine(latlons[i - 1], latlons[i]))

    # curvature per interior point
    pts = []  # (offset_m, radius, sign)
    for i in range(1, len(xy) - 1):
        r, sign = circumradius(xy[i - 1], xy[i], xy[i + 1])
        pts.append((cum[i], r, sign))

    # segment into same-sign sub-threshold runs
    corners = []
    run = []
    def close_run(run):
        if len(run) < MIN_CORNER_POINTS:
            return None
        offs = [p[0] for p in run]
        radii = [p[1] for p in run]
        third = max(1, len(radii) // 3)
        return {
            "start": offs[0], "end": offs[-1],
            "apex": offs[radii.index(min(radii))],
            "min_r": min(radii),
            "entry_r": sum(radii[:third]) / third,
            "exit_r": sum(radii[-third:]) / third,
            "arc": offs[-1] - offs[0],
            "dir": "LEFT" if run[0][2] > 0 else "RIGHT",
        }

    for off, r, sign in pts:
        if r < STRAIGHT_RADIUS_M and sign != 0:
            if run and sign != run[-1][2]:
                c = close_run(run)
                if c: corners.append(c)
                run = []
            run.append((off, r, sign))
        else:
            if run:
                c = close_run(run)
                if c: corners.append(c)
                run = []
    if run:
        c = close_run(run)
        if c: corners.append(c)

    # merge same-direction corners separated by a very short straight
    merged = []
    for c in corners:
        if merged and c["dir"] == merged[-1]["dir"] and c["start"] - merged[-1]["end"] < MERGE_GAP_M:
            m = merged[-1]
            m["end"] = c["end"]
            m["arc"] = m["end"] - m["start"]
            if c["min_r"] < m["min_r"]:
                m["min_r"] = c["min_r"]
                m["apex"] = c["apex"]
            m["exit_r"] = c["exit_r"]
        else:
            merged.append(dict(c))

    # confidence: source node density over the edge. OSM ways digitised with one node
    # every 100 m can't support a confident hairpin claim; one every 15 m can.
    total_len = cum[-1] if cum[-1] > 0 else 1.0
    spacing = total_len / max(1, source_node_count - 1)
    density_conf = max(0.0, min(1.0, (60.0 - spacing) / 45.0))  # 15 m -> 1.0, 60 m -> 0.0
    for c in merged:
        # very short arcs at high claimed severity are suspect
        arc_conf = max(0.3, min(1.0, c["arc"] / 25.0))
        c["confidence"] = round(max(0.05, density_conf * arc_conf), 3)
    return merged


def parse_maxspeed(v):
    if not v:
        return None
    v = v.strip().lower()
    try:
        if v.endswith("mph"):
            return int(round(float(v[:-3].strip()) * 1.609344))
        return int(float(v))
    except ValueError:
        return None


def parse_width_m(v):
    """Metric width tags only ("3", "2.5", "3 m"); anything else -> None."""
    if not v:
        return None
    v = v.strip().lower()
    if v.endswith("m"):
        v = v[:-1].strip()
    try:
        return float(v)
    except ValueError:
        return None


class Elevation:
    """
    Metres above sea level, from cached open terrain tiles (no key, no account).

    Terrarium encoding: elevation = R*256 + G + B/256 - 32768. Bilinear between
    pixels, because a road running diagonally across a 25 m grid otherwise reads
    as a staircase and every gentle slope looks like a series of steps.
    """

    def __init__(self, cache_dir, zoom=TERRAIN_ZOOM):
        self.zoom = zoom
        self.cache = cache_dir
        self.tiles = {}
        self.missing = set()
        os.makedirs(cache_dir, exist_ok=True)

    def _tile(self, tx, ty):
        key = (tx, ty)
        if key in self.tiles:
            return self.tiles[key]
        if key in self.missing:
            return None
        path = os.path.join(self.cache, f"{self.zoom}_{tx}_{ty}.png")
        data = None
        if os.path.exists(path):
            data = open(path, "rb").read()
        else:
            url = TERRAIN_URL.format(z=self.zoom, x=tx, y=ty)
            for attempt in range(3):
                try:
                    data = urllib.request.urlopen(url, timeout=30).read()
                    open(path, "wb").write(data)
                    break
                except Exception:
                    time.sleep(1 + attempt)
            if data is None:
                self.missing.add(key)
                return None
        try:
            im = Image.open(io.BytesIO(data)).convert("RGB")
        except Exception:
            self.missing.add(key)
            return None
        px = im.load()
        grid = [[0.0] * 256 for _ in range(256)]
        for y in range(256):
            row = grid[y]
            for x in range(256):
                r, g, b = px[x, y]
                row[x] = (r * 256 + g + b / 256.0) - 32768.0
        if len(self.tiles) > 40:            # keep memory sane on a big region
            self.tiles.pop(next(iter(self.tiles)))
        self.tiles[key] = grid
        return grid

    def at(self, lat, lon):
        n = 2 ** self.zoom
        fx = (lon + 180.0) / 360.0 * n
        r = math.radians(lat)
        fy = (1.0 - math.log(math.tan(r) + 1.0 / math.cos(r)) / math.pi) / 2.0 * n
        tx, ty = int(fx), int(fy)
        # Pixel position within the tile, offset by half a pixel so bilinear
        # weights are centred on pixel centres rather than corners.
        px = (fx - tx) * 256.0 - 0.5
        py = (fy - ty) * 256.0 - 0.5
        x0, y0 = math.floor(px), math.floor(py)
        dx, dy = px - x0, py - y0

        def sample(ix, iy):
            ttx, tty, sx, sy = tx, ty, ix, iy
            if sx < 0: ttx, sx = ttx - 1, sx + 256
            if sx > 255: ttx, sx = ttx + 1, sx - 256
            if sy < 0: tty, sy = tty - 1, sy + 256
            if sy > 255: tty, sy = tty + 1, sy - 256
            g = self._tile(ttx, tty)
            return None if g is None else g[sy][sx]

        v00, v10 = sample(x0, y0), sample(x0 + 1, y0)
        v01, v11 = sample(x0, y0 + 1), sample(x0 + 1, y0 + 1)
        if None in (v00, v10, v01, v11):
            return next((v for v in (v00, v10, v01, v11) if v is not None), None)
        return (v00 * (1 - dx) * (1 - dy) + v10 * dx * (1 - dy) +
                v01 * (1 - dx) * dy + v11 * dx * dy)


def grade_at(profile, cum, offset_m, window_m=GRADE_WINDOW_M):
    """
    Average slope over the [window_m] leading up to [offset_m]. + = uphill.

    Corners frequently sit right at the start of an edge (edges are split at
    junctions, and a bend usually follows one), leaving no road behind them to
    measure. Rather than report those as flat — which silently discards the hill
    on nearly a fifth of all corners — fall back to a window centred on the
    corner, which still describes the slope the driver is braking on.
    """
    if not profile or len(profile) < 2:
        return 0.0

    def slope(a_m, b_m):
        i0 = min(range(len(cum)), key=lambda i: abs(cum[i] - a_m))
        i1 = min(range(len(cum)), key=lambda i: abs(cum[i] - b_m))
        if i1 <= i0 or cum[i1] - cum[i0] < 15.0:
            return None
        return (profile[i1] - profile[i0]) / (cum[i1] - cum[i0])

    g = slope(max(0.0, offset_m - window_m), offset_m)
    if g is None:
        half = window_m / 2.0
        g = slope(max(0.0, offset_m - half), min(cum[-1], offset_m + half))
    if g is None:
        return 0.0
    return max(-0.30, min(0.30, g))


def find_crests(profile, cum):
    """
    Summits where the road rises then falls sharply enough to hide what follows.

    Returns (offset_m, grade_change). A blind crest is the one hazard a corner
    map genuinely cannot express: the geometry beyond may be arrow-straight and
    still be completely invisible until you are on it.
    """
    out = []
    if len(profile) < 5:
        return out
    last_offset = -1e9
    for i in range(len(profile)):
        up = grade_at(profile, cum, cum[i], CREST_WINDOW_M)
        # Slope of the road AFTER this point.
        j = min(range(len(cum)), key=lambda k: abs(cum[k] - (cum[i] + CREST_WINDOW_M)))
        if j <= i or cum[j] - cum[i] < 20.0:
            continue
        down = (profile[j] - profile[i]) / (cum[j] - cum[i])
        change = up - down                      # rise then fall = positive
        if up > 0.015 and down < -0.015 and change >= CREST_MIN_GRADE_CHANGE:
            if cum[i] - last_offset > 200.0:    # one call per summit
                out.append((cum[i], change))
                last_offset = cum[i]
    return out


class Collector(osmium.SimpleHandler):
    def __init__(self, bbox):
        super().__init__()
        self.bbox = bbox  # (minlon, minlat, maxlon, maxlat) or None
        self.ways = []            # (way_id, [node_ids], tags dict)
        self.node_use = defaultdict(int)
        self.node_loc = {}
        self.hazard_nodes = {}    # node_id -> kind
        self.camera_nodes = {}    # node_id -> camera kind (subset of hazard_nodes)

    def in_bbox(self, lon, lat):
        if not self.bbox:
            return True
        return self.bbox[0] <= lon <= self.bbox[2] and self.bbox[1] <= lat <= self.bbox[3]

    def node(self, n):
        tags = {t.k: t.v for t in n.tags}
        cam = camera_kind(tags)
        if cam:
            self.hazard_nodes[n.id] = cam
            self.camera_nodes[n.id] = cam
            return
        for key, val, kind in HAZARD_NODE_TAGS:
            if kind is None:
                continue
            tv = tags.get(key)
            if tv and (val is None or tv == val):
                self.hazard_nodes[n.id] = kind
                break

    def way(self, w):
        hw = w.tags.get("highway")
        if hw not in DRIVABLE:
            return
        nodes = [n.ref for n in w.nodes]
        if len(nodes) < 2:
            return
        # oneway=-1 / reverse means "one way, AGAINST node order": normalise by
        # flipping the node list, so downstream logic only ever sees forward oneways.
        ow = w.tags.get("oneway")
        if ow in ("-1", "reverse"):
            nodes.reverse()
            oneway = 1
        else:
            oneway = 1 if ow in ("yes", "1", "true") else 0
        # Narrow bridges: bridge tag plus a metric width under 3.5 m earns a caution.
        width = parse_width_m(w.tags.get("maxwidth")) or parse_width_m(w.tags.get("width"))
        narrow_bridge = (w.tags.get("bridge") or "no") != "no" and width is not None and width <= 3.5
        tags = {
            "highway": hw,
            "name": w.tags.get("name"),
            "ref": w.tags.get("ref"),
            "maxspeed": parse_maxspeed(w.tags.get("maxspeed")),
            "oneway": oneway,
            "narrow_bridge": narrow_bridge,
        }
        self.ways.append((w.id, nodes, tags))
        for nid in nodes:
            self.node_use[nid] += 1


class LocationLoader(osmium.SimpleHandler):
    """Second pass: store locations only for nodes the drivable graph uses."""
    def __init__(self, wanted, hazard_ids):
        super().__init__()
        self.wanted = wanted
        self.hazard_ids = hazard_ids
        self.loc = {}

    def node(self, n):
        if n.id in self.wanted or n.id in self.hazard_ids:
            self.loc[n.id] = (n.location.lat, n.location.lon)


def cell_of(lat, lon):
    """0.01 degree grid cell key (~1.1 km x 0.7 km at UK latitudes)."""
    return f"{int(math.floor(lat * 100))}:{int(math.floor(lon * 100))}"


def pack_geometry(latlons):
    return struct.pack(f"<{len(latlons) * 2}d", *[v for p in latlons for v in p])


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(1)
    src, dst = sys.argv[1], sys.argv[2]
    bbox = tuple(map(float, sys.argv[3:7])) if len(sys.argv) >= 7 else None

    print(f"pass 1: ways from {src}")
    col = Collector(bbox)
    col.apply_file(src)
    print(f"  {len(col.ways)} drivable ways, {len(col.hazard_nodes)} hazard nodes")

    wanted = set()
    for _, nodes, _ in col.ways:
        wanted.update(nodes)
    print("pass 2: node locations")
    loader = LocationLoader(wanted, set(col.hazard_nodes))
    loader.apply_file(src)
    loc = loader.loc

    # Junction nodes: used by >1 way, or way endpoints.
    junction_nodes = set()
    for wid, nodes, _ in col.ways:
        junction_nodes.add(nodes[0])
        junction_nodes.add(nodes[-1])
        for nid in nodes[1:-1]:
            if col.node_use[nid] > 1:
                junction_nodes.add(nid)

    # Split ways into edges at junction nodes; drop nodes without locations.
    print("building edges")
    edges = []   # dicts
    edge_id = 0
    junction_edges = defaultdict(list)
    for wid, nodes, tags in col.ways:
        run = []
        for nid in nodes:
            if nid not in loc:
                if len(run) >= 2:
                    edges_from_run(run, tags, edges, junction_edges)
                run = []
                continue
            run.append(nid)
            if nid in junction_nodes and len(run) >= 2:
                edges_from_run(run, tags, edges, junction_edges)
                run = [nid]
        if len(run) >= 2:
            edges_from_run(run, tags, edges, junction_edges)

    # Speed cameras sit on a post BESIDE the carriageway, so they are almost never
    # members of the road way — they have to be snapped to the nearest edge by
    # distance. Index them into a 3x3 block of cells so one cell lookup per edge
    # finds every camera that could possibly be near it.
    cam_by_cell = defaultdict(list)
    for nid, kind in col.camera_nodes.items():
        if nid not in loc:
            continue
        la, lo = loc[nid]
        for dla in (-0.01, 0.0, 0.01):
            for dlo in (-0.01, 0.0, 0.01):
                cam_by_cell[cell_of(la + dla, lo + dlo)].append(nid)
    cam_best = {}   # node_id -> (distance_m, edge_id, offset_m)
    print(f"  {len(col.camera_nodes)} speed cameras to snap")

    terrain_cache = os.path.join(os.path.expanduser("~"), ".rallydev", "downloads", "terrain")
    elev = Elevation(terrain_cache)
    print(f"terrain tiles cached in {terrain_cache}")

    # Process geometry per edge.
    print(f"processing {len(edges)} edges")
    out_edges, out_corners, out_hazards, cells = [], [], [], []
    cid = 0
    for eid, e in enumerate(edges):
        latlons = [loc[n] for n in e["nodes"]]
        if bbox and not any(col.in_bbox(lo, la) for la, lo in latlons):
            continue
        # smooth in local xy, then convert back
        lat0, lon0 = latlons[0]
        xy = [to_xy(la, lo, lat0, lon0) for la, lo in latlons]
        sm = chaikin(xy)
        cos0 = math.cos(math.radians(lat0))
        sm_ll = [(lat0 + math.degrees(y / EARTH_R), lon0 + math.degrees(x / (EARTH_R * cos0)))
                 for x, y in sm]
        rs = resample(sm_ll)
        if len(rs) < 2:
            continue
        length = sum(haversine(rs[i - 1], rs[i]) for i in range(1, len(rs)))
        if length < 10:
            continue
        corners = extract_corners(rs, source_node_count=len(latlons))
        out_edges.append((eid, e["from"], e["to"], length, e["tags"]["name"], e["tags"]["ref"],
                          e["tags"]["highway"], e["tags"]["maxspeed"], e["tags"]["oneway"],
                          pack_geometry(rs)))

        # Elevation along this edge, and what it implies.
        rs_cum2 = [0.0]
        for i in range(1, len(rs)):
            rs_cum2.append(rs_cum2[-1] + haversine(rs[i - 1], rs[i]))
        profile = []
        for la, lo in rs:
            v = elev.at(la, lo)
            profile.append(v if v is not None else (profile[-1] if profile else 0.0))
        if profile and any(v != 0.0 for v in profile):
            for off, change in find_crests(profile, rs_cum2):
                out_hazards.append((eid, off, "CREST"))
        else:
            profile = []

        for c in corners:
            grade = grade_at(profile, rs_cum2, c["start"]) if profile else 0.0
            out_corners.append((cid, eid, c["start"], c["apex"], c["end"], c["dir"],
                                c["min_r"], c["entry_r"], c["exit_r"], c["arc"],
                                c["confidence"], grade))
            cid += 1
        # hazards on this edge — offsets measured along the SAME smoothed/resampled
        # geometry the app stores. Measuring along the raw node polyline drifts late
        # through bends (smoothing shortens the path) and can even exceed the stored
        # edge length, which silently drops the hazard on reverse traversal.
        rs_cum = [0.0]
        for i in range(1, len(rs)):
            rs_cum.append(rs_cum[-1] + haversine(rs[i - 1], rs[i]))
        for nid in e["nodes"]:
            kind = col.hazard_nodes.get(nid)
            # Cameras go through the snapping pass below — emitting them here too
            # would call the same camera twice.
            if kind and nid in loc and nid not in col.camera_nodes:
                hz = loc[nid]
                j = min(range(len(rs)), key=lambda k: haversine(rs[k], hz))
                out_hazards.append((eid, rs_cum[j], kind))
        if e["tags"].get("narrow_bridge"):
            out_hazards.append((eid, length / 2.0, "NARROW_BRIDGE"))
        seen = set()
        for la, lo in rs:
            k = cell_of(la, lo)
            if k not in seen:
                seen.add(k)
                cells.append((k, eid))
        # Snap any nearby camera to this edge, keeping the closest edge overall.
        nearby_cams = set()
        for k in seen:
            nearby_cams.update(cam_by_cell.get(k, ()))
        for cam_id in nearby_cams:
            cla, clo = loc[cam_id]
            bd, boff = None, 0.0
            for j, p in enumerate(rs):
                d = haversine(p, (cla, clo))
                if bd is None or d < bd:
                    bd, boff = d, rs_cum[j]
            prev = cam_best.get(cam_id)
            if bd is not None and (prev is None or bd < prev[0]):
                cam_best[cam_id] = (bd, eid, boff)

    # Emit snapped cameras. 45 m covers a camera on the far verge of a dual
    # carriageway without dragging one in from the parallel road.
    #
    # A gantry is mapped as one node per camera head, and both carriageways of a
    # dual sit within a few metres, so a single enforcement point can arrive here
    # as four nodes. Declustered per edge: one call per site, because "camera
    # camera camera camera" is worse than no call at all.
    per_edge = defaultdict(list)
    for cam_id, (d, eid, off) in cam_best.items():
        if d <= 45.0:
            per_edge[eid].append((off, col.camera_nodes[cam_id]))
    snapped = 0
    for eid, items in per_edge.items():
        items.sort()
        last_off = None
        for off, kind in items:
            if last_off is not None and off - last_off < CAMERA_CLUSTER_M:
                continue
            out_hazards.append((eid, off, kind))
            last_off = off
            snapped += 1
    print(f"  snapped {snapped} camera sites from {len(col.camera_nodes)} camera nodes")

    kept = {e[0] for e in out_edges}
    out_junctions = []
    for nid in junction_nodes:
        if nid in loc:
            eids = [i for i in junction_edges.get(nid, []) if i in kept]
            if eids:
                la, lo = loc[nid]
                out_junctions.append((nid, la, lo, ",".join(map(str, eids))))

    crests = sum(1 for h in out_hazards if h[2] == "CREST")
    print(f"  {crests} blind crests; {len(elev.missing)} terrain tiles unavailable")
    print(f"writing {dst}: {len(out_edges)} edges, {len(out_corners)} corners, "
          f"{len(out_hazards)} hazards, {len(out_junctions)} junctions")
    db = sqlite3.connect(dst)
    db.executescript("""
        PRAGMA journal_mode=OFF;
        DROP TABLE IF EXISTS meta; DROP TABLE IF EXISTS edges; DROP TABLE IF EXISTS junctions;
        DROP TABLE IF EXISTS corners; DROP TABLE IF EXISTS hazards; DROP TABLE IF EXISTS edge_cells;
        CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT);
        CREATE TABLE edges(id INTEGER PRIMARY KEY, from_node INTEGER, to_node INTEGER,
            length_m REAL, name TEXT, ref TEXT, highway TEXT, maxspeed_kph INTEGER,
            oneway INTEGER, geometry BLOB);
        CREATE TABLE junctions(node_id INTEGER PRIMARY KEY, lat REAL, lon REAL, edge_ids TEXT);
        CREATE TABLE corners(id INTEGER PRIMARY KEY, edge_id INTEGER, start_m REAL, apex_m REAL,
            end_m REAL, direction TEXT, min_r REAL, entry_r REAL, exit_r REAL, arc_m REAL,
            confidence REAL, approach_grade REAL DEFAULT 0);
        CREATE TABLE hazards(edge_id INTEGER, offset_m REAL, kind TEXT);
        CREATE TABLE edge_cells(cell TEXT, edge_id INTEGER);
        CREATE INDEX idx_cells ON edge_cells(cell);
        CREATE INDEX idx_corner_edge ON corners(edge_id);
        CREATE INDEX idx_hazard_edge ON hazards(edge_id);
    """)
    db.executemany("INSERT INTO edges VALUES (?,?,?,?,?,?,?,?,?,?)", out_edges)
    db.executemany("INSERT INTO junctions VALUES (?,?,?,?)", out_junctions)
    db.executemany("INSERT INTO corners VALUES (?,?,?,?,?,?,?,?,?,?,?,?)", out_corners)
    db.executemany("INSERT INTO hazards VALUES (?,?,?)", out_hazards)
    db.executemany("INSERT INTO edge_cells VALUES (?,?)", cells)
    db.execute("INSERT INTO meta VALUES ('schema','2')")
    db.execute("INSERT INTO meta VALUES ('source',?)", (src,))
    db.commit()
    db.execute("VACUUM")
    db.close()
    print("done")


def edges_from_run(run, tags, edges, junction_edges):
    eid = len(edges)
    edges.append({"nodes": list(run), "from": run[0], "to": run[-1], "tags": tags})
    junction_edges[run[0]].append(eid)
    junction_edges[run[-1]].append(eid)


if __name__ == "__main__":
    main()
