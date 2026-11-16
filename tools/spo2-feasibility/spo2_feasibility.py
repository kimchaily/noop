#!/usr/bin/env python3
"""Does a Choop backup hold enough to estimate blood oxygen? A read-only experiment.

WHY THIS IS AN EXPERIMENT AND NOT A FEATURE
-------------------------------------------
A WHOOP 4.0 stores one red and one infrared reading per second (`spo2Sample.red` / `.ir`). Choop keeps
them and computes nothing from them, on purpose: WHOOP's own percentage comes from a calibration that
lives in WHOOP's app and servers, not on the strap, so there is nothing to read out of the Bluetooth
stream and nothing to reverse-engineer from a strap alone.

What CAN be asked of the stored readings is narrower, and answerable:

  1. Is the pulse in them at all?  A pulse oximeter compares how strongly the heartbeat modulates red
     against infrared. One reading per second is far below the heart rate, so the beat cannot be seen
     directly. It does not vanish, though: it ALIASES to |HR/60 - round(HR/60)| Hz, a frequency that
     can be predicted from the heart rate Choop already stores. If the red/infrared series shows a peak
     that follows that predicted frequency as the heart rate drifts through the night, the readings are
     raw optical samples and still carry the pulse. If it does not, they are something else (a slow
     average, a level, a gain setting) and no arithmetic will get oxygen out of them.

  2. If the pulse is there, how strongly does it show in red relative to infrared?  That "ratio of
     ratios" R is the quantity every pulse oximeter turns into a percentage.

  3. Does R move with real oxygen readings?  Only checkable against references you supply: the nightly
     figure from the WHOOP app, or a fingertip oximeter. Without them R is a number with no scale.

The honest ceiling, even if all three come out well, is a personally calibrated ESTIMATE that tracks
your own nights. It is not WHOOP's algorithm and it is not a medical measurement.

A WHOOP 5.0 / MG stores no red/infrared pair in the record layout Choop decodes, so for those straps
this stops at the inventory and says so.

USAGE
-----
    python3 spo2_feasibility.py backup.noopbak
    python3 spo2_feasibility.py backup.noopbak --ref 2026-11-10=96 --ref 2026-11-11=97 ...
    python3 spo2_feasibility.py --self-test

`--ref DAY=PERCENT` is the oxygen reading for the night that ENDED on that local day. `--tz-hours`
is your offset from UTC (default 1). Nothing is written to the backup: it is opened read-only, and only
counts and aggregates are printed. Needs Python 3.8+ and nothing else.
"""

from __future__ import annotations

import argparse
import math
import os
import random
import sqlite3
import statistics
import sys
import tempfile
import zipfile

WINDOW = 120            # seconds per analysis window
TREND = 15              # seconds of moving average removed as the slow baseline
ALIAS_LO, ALIAS_HI = 0.08, 0.45   # predicted alias must sit clear of the baseline and of Nyquist
HIT_TOL = 0.02          # Hz: how close the spectral peak must sit to the prediction to count


# ---------------------------------------------------------------- small numeric helpers (stdlib only)

def detrend(xs):
    """Remove a centred moving average; returns (residual, baseline)."""
    n, half = len(xs), TREND // 2
    csum = [0.0]
    for v in xs:
        csum.append(csum[-1] + v)
    base = []
    for i in range(n):
        lo, hi = max(0, i - half), min(n, i + half + 1)
        base.append((csum[hi] - csum[lo]) / (hi - lo))
    return [x - b for x, b in zip(xs, base)], base


def spectrum(seg):
    """Hann-windowed power at k/len Hz for k = 1 .. len/2 - 1 (1 Hz sampling)."""
    n = len(seg)
    mean = sum(seg) / n
    w = [(v - mean) * (0.5 - 0.5 * math.cos(2 * math.pi * i / (n - 1))) for i, v in enumerate(seg)]
    out = []
    for k in range(1, n // 2):
        re = sum(v * math.cos(2 * math.pi * k * i / n) for i, v in enumerate(w))
        im = sum(v * math.sin(2 * math.pi * k * i / n) for i, v in enumerate(w))
        out.append((k / n, re * re + im * im))
    return out


def alias_of(bpm):
    f = bpm / 60.0
    return abs(f - round(f))


def mad(xs):
    m = statistics.median(xs)
    return statistics.median([abs(x - m) for x in xs])


def spearman(a, b):
    def ranks(v):
        order = sorted(range(len(v)), key=lambda i: v[i])
        r = [0.0] * len(v)
        i = 0
        while i < len(order):
            j = i
            while j + 1 < len(order) and v[order[j + 1]] == v[order[i]]:
                j += 1
            for k in range(i, j + 1):
                r[order[k]] = (i + j) / 2.0
            i = j + 1
        return r
    if len(a) < 3:
        return None
    ra, rb = ranks(a), ranks(b)
    ma, mb = sum(ra) / len(ra), sum(rb) / len(rb)
    num = sum((x - ma) * (y - mb) for x, y in zip(ra, rb))
    den = math.sqrt(sum((x - ma) ** 2 for x in ra) * sum((y - mb) ** 2 for y in rb))
    return num / den if den else None


# ---------------------------------------------------------------- one night

class Night:
    def __init__(self, day):
        self.day = day
        self.samples = 0
        self.windows = 0
        self.testable = 0        # windows whose predicted alias was in the usable band
        self.hits = 0            # ... whose spectral peak sat on the prediction
        self.control_hits = 0    # ... or on the prediction for a deliberately wrong heart rate
        self.predicted = []
        self.observed = []
        self.ratios = []
        self.ac_ir_pct = []
        self.corr = []

    @property
    def ratio(self):
        return statistics.median(self.ratios) if self.ratios else None


def analyse_night(day, rows, hr_by_ts):
    """rows: [(ts, red, ir)] ascending for one night. hr_by_ts: {ts: bpm}."""
    night = Night(day)
    night.samples = len(rows)
    # Cut into runs of consecutive seconds, then into WINDOW-second windows.
    runs, cur = [], [rows[0]]
    for prev, row in zip(rows, rows[1:]):
        if row[0] - prev[0] == 1:
            cur.append(row)
        else:
            runs.append(cur)
            cur = [row]
    runs.append(cur)
    windows = []
    for run in runs:
        for k in range(0, len(run) - WINDOW + 1, WINDOW):
            windows.append(run[k:k + WINDOW])
    if not windows:
        return night
    # Throw out the windows the wrist was moving in: their residual is motion, not pulse.
    spreads = []
    prepared = []
    for win in windows:
        red = [float(r[1]) for r in win]
        ir = [float(r[2]) for r in win]
        if min(red) <= 0 or min(ir) <= 0:
            continue
        red_ac, red_dc = detrend(red)
        ir_ac, ir_dc = detrend(ir)
        spread = mad(ir_ac)
        spreads.append(spread)
        prepared.append((win, red_ac, red_dc, ir_ac, ir_dc, spread))
    if not prepared:
        return night
    quiet = statistics.median(spreads) * 3.0
    for win, red_ac, red_dc, ir_ac, ir_dc, spread in prepared:
        if spread == 0 or spread > quiet:
            continue
        night.windows += 1
        dc_r, dc_i = statistics.fmean(red_dc), statistics.fmean(ir_dc)
        x = [v / dc_i for v in ir_ac]
        y = [v / dc_r for v in red_ac]
        sxx = sum(v * v for v in x)
        syy = sum(v * v for v in y)
        sxy = sum(a * b for a, b in zip(x, y))
        if sxx > 0 and syy > 0:
            night.corr.append(sxy / math.sqrt(sxx * syy))
            # Spread ratio rather than regression slope: it survives red and infrared being read a
            # fraction of a second apart, which a slope does not.
            night.ratios.append(math.sqrt(syy / sxx))
        night.ac_ir_pct.append(100.0 * 1.4826 * spread / dc_i)
        bpms = [hr_by_ts[r[0]] for r in win if r[0] in hr_by_ts]
        if len(bpms) < WINDOW // 2:
            continue
        bpm = statistics.median(bpms)
        target = alias_of(bpm)
        if not (ALIAS_LO <= target <= ALIAS_HI):
            continue
        spec = spectrum(ir_ac)
        peak = max(spec, key=lambda fp: fp[1])[0]
        night.testable += 1
        night.predicted.append(target)
        night.observed.append(peak)
        if abs(peak - target) <= HIT_TOL:
            night.hits += 1
        # Control: the alias for a heart rate 7 bpm off. Breathing sits at a fixed frequency and will
        # land on a prediction by accident now and then; it lands on the control just as often.
        if abs(peak - alias_of(bpm + 7.0)) <= HIT_TOL:
            night.control_hits += 1
    return night


# ---------------------------------------------------------------- reading the backup

def open_backup(path):
    """Open a .noopbak (zip) or a bare sqlite file read-only. Returns (connection, tempdir or None)."""
    tmp = None
    db = path
    if zipfile.is_zipfile(path):
        tmp = tempfile.TemporaryDirectory()
        with zipfile.ZipFile(path) as z:
            names = [n for n in z.namelist() if n.endswith(".sqlite")]
            if not names:
                raise SystemExit("No .sqlite file inside the backup.")
            z.extract(names[0], tmp.name)
            db = os.path.join(tmp.name, names[0])
    con = sqlite3.connect("file:" + os.path.abspath(db) + "?mode=ro", uri=True)
    return con, tmp


def nights_for(con, device, tz_seconds):
    """Group a device's red/IR rows into nights: local 23:00 to 07:00, labelled by the morning's date."""
    cur = con.execute("SELECT ts, red, ir FROM spo2Sample WHERE deviceId = ? ORDER BY ts", (device,))
    nights = {}
    for ts, red, ir in cur:
        local = ts + tz_seconds
        hour = (local % 86400) // 3600
        if hour >= 23:
            day_index = local // 86400 + 1
        elif hour < 7:
            day_index = local // 86400
        else:
            continue
        nights.setdefault(day_index, []).append((ts, red, ir))
    return nights


def day_label(day_index):
    import datetime
    return (datetime.date(1970, 1, 1) + datetime.timedelta(days=day_index)).isoformat()


def run(path, refs, tz_hours, out=print):
    con, tmp = open_backup(path)
    try:
        tables = {r[0] for r in con.execute("SELECT name FROM sqlite_master WHERE type='table'")}
        if "spo2Sample" not in tables:
            out("This file has no spo2Sample table. Is it a Choop backup?")
            return {"verdict": "no-table"}
        inventory = con.execute(
            "SELECT deviceId, COUNT(*), MIN(ts), MAX(ts), MIN(red), MAX(red), MIN(ir), MAX(ir) "
            "FROM spo2Sample GROUP BY deviceId").fetchall()
        out("1. WHAT THE BACKUP HOLDS")
        if not inventory:
            out("   No red/infrared readings at all.")
            out("   That is expected for a WHOOP 5.0 / MG: the record layout Choop decodes for those straps")
            out("   carries no red/infrared pair. There is nothing here to estimate oxygen from.")
            return {"verdict": "no-data"}
        for dev, n, t0, t1, r0, r1, i0, i1 in inventory:
            out(f"   strap {dev[:6]}...: {n:,} readings over {(t1 - t0) / 86400:.1f} days, "
                f"red {r0}..{r1}, infrared {i0}..{i1}")
        tz = int(tz_hours * 3600)
        results = []
        for dev, n, *_ in inventory:
            grouped = nights_for(con, dev, tz)
            for day_index in sorted(grouped)[-30:]:
                rows = grouped[day_index]
                if len(rows) < 3600:
                    continue
                hr = dict(con.execute(
                    "SELECT ts, bpm FROM hrSample WHERE deviceId = ? AND ts BETWEEN ? AND ?",
                    (dev, rows[0][0], rows[-1][0])))
                results.append(analyse_night(day_label(day_index), rows, hr))
        results = [r for r in results if r.windows > 0]
        if not results:
            out("   Readings exist but no night had an hour of them between 23:00 and 07:00.")
            return {"verdict": "too-little"}

        out("\n2. IS THE PULSE IN THE READINGS?")
        out("   night        windows  testable  on-prediction  on-control  infrared AC/DC  red~IR corr")
        for r in results:
            ac = statistics.median(r.ac_ir_pct) if r.ac_ir_pct else float("nan")
            co = statistics.median(r.corr) if r.corr else float("nan")
            out(f"   {r.day}   {r.windows:>6d}  {r.testable:>8d}  {r.hits:>13d}  {r.control_hits:>10d}"
                f"  {ac:>12.3f} %  {co:>10.2f}")
        testable = sum(r.testable for r in results)
        hits = sum(r.hits for r in results)
        control = sum(r.control_hits for r in results)
        pred = [p for r in results for p in r.predicted]
        obs = [o for r in results for o in r.observed]
        follow = spearman(pred, obs)
        hit_rate = hits / testable if testable else 0.0
        control_rate = control / testable if testable else 0.0
        follow_text = "none, the peak never moves" if follow is None else f"{follow:+.2f}"
        out(f"   Peak on the predicted alias in {hit_rate:.0%} of {testable} windows; on the wrong-heart-rate")
        out(f"   control in {control_rate:.0%}. Peak frequency follows the prediction: rank correlation {follow_text}.")
        pulse = (testable >= 30 and hit_rate >= 0.25 and hit_rate >= 2.5 * max(control_rate, 0.02)
                 and follow is not None and follow >= 0.3)
        if testable < 30:
            out("   Too few testable windows to say either way.")
            verdict = "undetermined"
        elif pulse:
            out("   YES. The readings are raw optical samples and the heartbeat is in them.")
            verdict = "pulse"
        else:
            out("   NO. The series does not carry the heartbeat. Whatever these two numbers are, oxygen")
            out("   cannot be computed from them, and nothing further below should be trusted.")
            verdict = "no-pulse"

        out("\n3. RED AGAINST INFRARED (R), PER NIGHT")
        if verdict != "pulse":
            # A ratio can always be computed, and the textbook curve turns any ratio into a believable
            # percentage. Without the pulse it is the ratio of breathing and drift, so no percentage is
            # printed here: a plausible number from the wrong signal is the exact failure to avoid.
            out("   R is listed without a percentage: with no pulse found it measures breathing and drift,")
            out("   and would convert to a believable but meaningless oxygen figure.")
        for r in results:
            if r.ratio is None:
                continue
            if verdict == "pulse":
                out(f"   {r.day}   R = {r.ratio:.3f}   textbook curve 110 - 25R = {110 - 25 * r.ratio:5.1f} %"
                    "   (uncalibrated, shown only for scale)")
            else:
                out(f"   {r.day}   R = {r.ratio:.3f}")

        paired = [(r.ratio, refs[r.day]) for r in results if r.day in refs and r.ratio is not None]
        out("\n4. DOES R MOVE WITH YOUR REFERENCE READINGS?")
        rho = None
        if len(paired) < 5:
            out(f"   {len(paired)} night(s) have both an R and a reference. At least 5 are needed, 14 is better:")
            out("   pass --ref YYYY-MM-DD=PERCENT for nights the WHOOP app or a fingertip oximeter gave a figure.")
        else:
            rho = spearman([p[0] for p in paired], [p[1] for p in paired])
            spread = max(p[1] for p in paired) - min(p[1] for p in paired)
            out(f"   {len(paired)} nights. Rank correlation between R and the reference: {rho:+.2f} "
                "(a real oxygen signal is NEGATIVE: more red modulation means less oxygen).")
            if spread < 2:
                out(f"   Your references span only {spread:.0f} point(s). That is too narrow to prove or disprove")
                out("   anything: a healthy sleeper sits at 95-98 every night and so would a broken estimate.")
            elif rho <= -0.5 and verdict == "pulse":
                out("   R tracks the reference. A personally calibrated estimate is worth building.")
            else:
                out("   R does not track the reference. No estimate should be shown from this.")
        return {"verdict": verdict, "hit_rate": hit_rate, "control_rate": control_rate,
                "follow": follow, "rho": rho, "nights": len(results)}
    finally:
        con.close()
        if tmp is not None:
            tmp.cleanup()


# ---------------------------------------------------------------- self-test on made-up data

def _synthetic_db(path, stored, nights=8, seed=3):
    """A backup with `nights` nights of red/IR at known oxygen levels, kept `stored`-wise per second."""
    rng = random.Random(seed)
    con = sqlite3.connect(path)
    con.execute("CREATE TABLE spo2Sample (deviceId TEXT, ts INTEGER, red INTEGER, ir INTEGER, synced INTEGER, "
                "PRIMARY KEY(deviceId, ts))")
    con.execute("CREATE TABLE hrSample (deviceId TEXT, ts INTEGER, bpm INTEGER, synced INTEGER, "
                "PRIMARY KEY(deviceId, ts))")
    refs = {}
    fs = 25
    base_day = 20000                                   # some day in 2024, UTC, tz 0 in the test
    for night in range(nights):
        spo2 = 90.0 + night                            # 90 .. 97
        ratio = (110.0 - spo2) / 25.0
        start = (base_day + night) * 86400 - 3600      # 23:00
        refs[day_label(base_day + night)] = spo2
        phase = 0.0
        red_rows, hr_rows = [], []
        for sec in range(3 * 3600):                    # three hours is plenty
            ts = start + sec
            bpm = 49 + 5 * math.sin(2 * math.pi * sec / 2400.0) + 2 * math.sin(2 * math.pi * sec / 310.0)
            reds, irs = [], []
            for k in range(fs):
                t = sec + k / fs
                phase += 2 * math.pi * (bpm / 60.0) / fs
                pulse = math.sin(phase) + 0.3 * math.sin(2 * phase + 0.6)
                breath = math.sin(2 * math.pi * 0.19 * t)
                reds.append(21000 * (1 + ratio * 0.012 * pulse + 0.002 * breath + rng.gauss(0, 0.0004)))
                irs.append(34000 * (1 + 0.012 * pulse + 0.003 * breath + rng.gauss(0, 0.0004)))
            if stored == "instant":
                red, ir = reds[0], irs[0]
            else:                                      # "level": a slow level with no pulse left in it
                red = 21000 * (1 + 0.002 * math.sin(2 * math.pi * 0.19 * sec) + rng.gauss(0, 0.0004))
                ir = 34000 * (1 + 0.003 * math.sin(2 * math.pi * 0.19 * sec) + rng.gauss(0, 0.0004))
            red_rows.append(("strap-under-test", ts, int(round(red)), int(round(ir)), 0))
            hr_rows.append(("strap-under-test", ts, int(round(bpm)), 0))
        con.executemany("INSERT INTO spo2Sample VALUES (?,?,?,?,?)", red_rows)
        con.executemany("INSERT INTO hrSample VALUES (?,?,?,?)", hr_rows)
    con.commit()
    con.close()
    return refs


def self_test():
    quiet = lambda *_: None
    with tempfile.TemporaryDirectory() as d:
        raw = os.path.join(d, "raw.sqlite")
        refs = _synthetic_db(raw, "instant")
        got = run(raw, refs, 0, out=quiet)
        assert got["verdict"] == "pulse", got
        assert got["rho"] is not None and got["rho"] <= -0.9, got

        level = os.path.join(d, "level.sqlite")
        refs = _synthetic_db(level, "level")
        got = run(level, refs, 0, out=quiet)
        assert got["verdict"] == "no-pulse", got

        empty = os.path.join(d, "empty.sqlite")
        con = sqlite3.connect(empty)
        con.execute("CREATE TABLE spo2Sample (deviceId TEXT, ts INTEGER, red INTEGER, ir INTEGER)")
        con.execute("CREATE TABLE hrSample (deviceId TEXT, ts INTEGER, bpm INTEGER)")
        con.commit()
        con.close()
        assert run(empty, {}, 0, out=quiet)["verdict"] == "no-data"

        packed = os.path.join(d, "backup.noopbak")
        with zipfile.ZipFile(packed, "w") as z:
            z.write(empty, "noop-backup.sqlite")
        assert run(packed, {}, 0, out=quiet)["verdict"] == "no-data"
    print("self-test passed: raw samples are recognised and tracked, a pulse-free level is rejected, "
          "an empty backup stops at the inventory.")


def main(argv=None):
    ap = argparse.ArgumentParser(description="Read-only: can this Choop backup support a blood-oxygen estimate?")
    ap.add_argument("backup", nargs="?", help="a .noopbak file, or the noop-backup.sqlite inside it")
    ap.add_argument("--ref", action="append", default=[], metavar="DAY=PERCENT",
                    help="a reference reading for the night that ended on DAY (YYYY-MM-DD); repeatable")
    ap.add_argument("--tz-hours", type=float, default=1.0, help="your offset from UTC in hours (default 1)")
    ap.add_argument("--self-test", action="store_true", help="run against made-up data and exit")
    args = ap.parse_args(argv)
    if args.self_test:
        self_test()
        return 0
    if not args.backup:
        ap.error("give a backup file, or --self-test")
    refs = {}
    for item in args.ref:
        day, _, pct = item.partition("=")
        refs[day.strip()] = float(pct)
    run(args.backup, refs, args.tz_hours)
    return 0


if __name__ == "__main__":
    sys.exit(main())
