#!/usr/bin/env python3
"""Independent check of the Java pass predictor.

The Java program uses Orekit for orbit propagation, frames and event detection. This script re-does the
physics with completely different code - the `sgp4` Python package (the reference implementation of the
SGP4 model) plus ~30 lines of NumPy geometry - and compares the results:

  1. PASS COUNT      For every satellite/station pair: scan the window in 1 s steps, count the stretches
                     above the elevation mask, compare with the number of passes in passes.csv.
  2. TIMING          For every pass in passes.csv: find AOS and LOS again by bisection and report how far the
                     Java times are off (in milliseconds).

If both programs agree, a bug would have to be present in two unrelated code bases at once, which is
unlikely - that is the whole point of an independent oracle.

Usage:
    java ... Main --tle data/demo_sats.tle --stations data/stations.csv --start 2026-10-24T00:00:00Z --out out
    python3 tools/crosscheck.py --tle data/demo_sats.tle --stations data/stations.csv \
            --passes out/passes.csv --start 2026-10-24T00:00:00Z --hours 24

Requires: pip install sgp4 numpy
Simplifications (all far below the accuracy of a TLE): Earth rotation by GMST only (no polar motion /
nutation), UT1 taken equal to UTC, station height above the WGS-84 ellipsoid.
"""
import argparse
import csv
import math
import sys
from datetime import datetime, timedelta, timezone

import numpy as np
from sgp4.api import Satrec, jday

WGS84_A = 6378.137            # km
WGS84_F = 1.0 / 298.257223563


def read_tles(path):
    lines = [l.rstrip() for l in open(path) if l.strip() and not l.startswith("#")]
    sats, i = {}, 0
    while i < len(lines):
        name = None
        if not lines[i].startswith("1 "):
            name = lines[i].strip()
            i += 1
        sats[name or f"SAT-{lines[i][2:7].strip()}"] = Satrec.twoline2rv(lines[i], lines[i + 1])
        i += 2
    return sats


def read_stations(path):
    out = {}
    for row in csv.reader(l for l in open(path) if l.strip() and not l.startswith("#")):
        name, lat, lon, alt, mask = row
        out[name.strip()] = (float(lat), float(lon), float(alt) / 1000.0, float(mask))
    return out


def parse_utc(text):
    return datetime.fromisoformat(text.replace("Z", "+00:00")).astimezone(timezone.utc)


def julian(dt_list):
    """Two-part Julian dates (jd, fraction) for an array of datetimes - the form sgp4 wants."""
    jd = np.empty(len(dt_list)); fr = np.empty(len(dt_list))
    for k, d in enumerate(dt_list):
        jd[k], fr[k] = jday(d.year, d.month, d.day, d.hour, d.minute, d.second + d.microsecond * 1e-6)
    return jd, fr


def gmst(jd, fr):
    """Greenwich mean sidereal time in radians (IAU-82 polynomial, UT1 taken equal to UTC)."""
    t = ((jd - 2451545.0) + fr) / 36525.0                     # Julian centuries since J2000
    seconds = 67310.54841 + (876600.0 * 3600 + 8640184.812866) * t + 0.093104 * t * t - 6.2e-6 * t ** 3
    return np.radians((seconds / 240.0) % 360.0)              # 240 s of sidereal time = 1 degree


def station_ecef(lat_deg, lon_deg, alt_km):
    lat, lon = math.radians(lat_deg), math.radians(lon_deg)
    e2 = WGS84_F * (2 - WGS84_F)
    n = WGS84_A / math.sqrt(1 - e2 * math.sin(lat) ** 2)
    return np.array([(n + alt_km) * math.cos(lat) * math.cos(lon),
                     (n + alt_km) * math.cos(lat) * math.sin(lon),
                     (n * (1 - e2) + alt_km) * math.sin(lat)])


def elevation_deg(sat, station, jd, fr):
    """Elevation (degrees) of `sat` seen from `station` at the given times (arrays)."""
    lat, lon, alt, _ = station
    jd = np.atleast_1d(jd); fr = np.atleast_1d(fr)
    err, r_teme, _ = sat.sgp4_array(jd, fr)
    if np.any(err != 0):
        raise RuntimeError("SGP4 error code(s): %s" % sorted(set(err.tolist())))
    theta = gmst(jd, fr)
    c, s = np.cos(theta), np.sin(theta)
    # TEME -> Earth-fixed: rotate by -GMST about z
    x = c * r_teme[:, 0] + s * r_teme[:, 1]
    y = -s * r_teme[:, 0] + c * r_teme[:, 1]
    z = r_teme[:, 2]
    d = np.stack([x, y, z], axis=1) - station_ecef(lat, lon, alt)
    la, lo = math.radians(lat), math.radians(lon)
    up = np.array([math.cos(la) * math.cos(lo), math.cos(la) * math.sin(lo), math.sin(la)])
    return np.degrees(np.arcsin((d @ up) / np.linalg.norm(d, axis=1)))


def elevation_at(sat, station, dt):
    jd, fr = julian([dt])
    return float(elevation_deg(sat, station, jd, fr)[0])


def refine(sat, station, t_guess, mask, rising):
    """Bisection for the mask crossing within +-10 s of t_guess. Returns a datetime or None."""
    lo, hi = t_guess - timedelta(seconds=10), t_guess + timedelta(seconds=10)
    f_lo = elevation_at(sat, station, lo) - mask
    f_hi = elevation_at(sat, station, hi) - mask
    if (f_lo < 0) != rising or (f_hi >= 0) != rising:
        return None
    for _ in range(40):
        mid = lo + (hi - lo) / 2
        if (elevation_at(sat, station, mid) - mask >= 0) == rising:
            hi = mid
        else:
            lo = mid
    return lo + (hi - lo) / 2


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--tle", required=True); ap.add_argument("--stations", required=True)
    ap.add_argument("--passes", required=True, help="passes.csv written by the Java program")
    ap.add_argument("--start", required=True); ap.add_argument("--hours", type=float, default=24.0)
    ap.add_argument("--tol-ms", type=float, default=250.0, help="largest accepted AOS/LOS difference")
    a = ap.parse_args()

    sats, stations = read_tles(a.tle), read_stations(a.stations)
    start = parse_utc(a.start)
    n = int(a.hours * 3600) + 1
    times = [start + timedelta(seconds=k) for k in range(n)]
    jd, fr = julian(times)

    rows = list(csv.DictReader(open(a.passes)))
    ok = True

    print("1. pass count (1 s scan vs Java)")
    for sname, sat in sats.items():
        for gname, st in stations.items():
            above = elevation_deg(sat, st, jd, fr) >= st[3]
            runs = int(np.sum(above[1:] & ~above[:-1])) + (1 if above[0] else 0)
            java = sum(1 for r in rows if r["satellite"] == sname and r["station"] == gname)
            flag = "ok" if runs == java else "MISMATCH"
            ok &= runs == java
            print(f"   {sname:11s} {gname:10s} oracle {runs:3d}   java {java:3d}   {flag}")

    print("2. AOS / LOS timing (bisection vs Java)")
    diffs = []
    for r in rows:
        sat, st = sats[r["satellite"]], stations[r["station"]]
        for key, rising, clipped in (("aos_utc", True, r["clipped_start"] == "true"),
                                     ("los_utc", False, r["clipped_end"] == "true")):
            if clipped:
                continue
            java_t = parse_utc(r[key])
            t = refine(sat, st, java_t, st[3], rising)
            if t is None:
                print("   no crossing found near", r["satellite"], r["station"], key, r[key]); ok = False
                continue
            diffs.append((java_t - t).total_seconds() * 1000.0)
    d = np.abs(np.array(diffs))
    print(f"   {len(d)} event times compared: mean |diff| {d.mean():.1f} ms, median {np.median(d):.1f} ms, "
          f"max {d.max():.1f} ms (tolerance {a.tol_ms:.0f} ms)")
    ok &= bool(d.max() <= a.tol_ms)
    print("RESULT:", "PASS" if ok else "FAIL")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
