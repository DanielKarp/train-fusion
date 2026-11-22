"""Compare a Kotlin replay dump with phone-sensor-fusion's features for one recording (a
made-up one: tools/check-port-synthetic), with the same checks and tolerances as
phone-sensor-fusion/app/compare.py's feature part. Needs no labels; recordings and caches are
read from and written to the directories given, not data/.

    python compare_features.py <recordings root> <id> <dump.jsonl> <scratch derived dir>
"""
import json
import sys
from pathlib import Path

import numpy as np

root, rid, dump, derived = sys.argv[1:5]
sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "phone-sensor-fusion"))
from common import paths  # noqa: E402

paths.RECORDINGS = Path(root)
paths.DERIVED = Path(derived)
from common.recording import Recording  # noqa: E402
from features import frames  # noqa: E402

f = frames.extract(Recording(rid))
ref = {"open_chime": f["open_chime"], "close_chime": f["close_chime"], "rumble_db": f["rumble_db"],
       "vib_low": f["vib_db"][:, 0], "vib_high": f["vib_db"][:, 1], "gyro_rms": f["gyro_rms"], "acc_mean": f["acc_mean"],
       "tone_spec": {str(b): f["tone_spec"][:, b].astype(float) for b in (150, 300, 475, 700)},
       "accel_spec": {str(b): f["accel_spec"][:, b].astype(float) for b in (100, 250, 500)},
       "baro_alt": f.get("baro_alt")}
rows = [json.loads(l) for l in Path(dump).read_text().splitlines() if l.strip()]
n = np.array([r["n"] for r in rows])
assert (np.diff(n) == 1).all() and n[0] == 0, "seconds missing from the dump"
T = min(len(rows), len(ref["rumble_db"]))
print(f"{len(rows)} s from Kotlin, {len(ref['rumble_db'])} s in the reference")
bad = 0


def col(key, sub=None):
    return np.array([(r[key] if sub is None else r[key][sub]) for r in rows[:T]], float)


def show(name, a, b, tol):
    global bad
    d = a - np.asarray(b, float)[:T]
    ok = np.percentile(np.abs(d), 95) <= tol
    bad += not ok
    print(f"   {name:22s} median |diff| {np.median(np.abs(d)):8.4f}   95 % {np.percentile(np.abs(d), 95):8.4f}   "
          f"max {np.abs(d).max():8.3f}   {'ok' if ok else 'DIFFERENT'}")


for key, tol in (("open_chime", 0.05), ("close_chime", 0.05), ("rumble_db", 0.3), ("vib_low", 0.3), ("vib_high", 0.3),
                 ("gyro_rms", 0.01)):
    show(key, col(key), ref[key], tol)
for i in range(3):
    show(f"acc_mean ({'xyz'[i]})", col("acc_mean", i), np.array(ref["acc_mean"])[:, i], 0.002)
for i, b in enumerate(("150", "300", "475", "700")):
    show(f"tone_spec[{b}]", col("tone_spec", i), ref["tone_spec"][b], 1.5)
late = {r["accel_n"]: r["accel_spec"] for r in rows if "accel_n" in r}
ks = np.array(sorted(k for k in late if k < T))
for i, b in enumerate(("100", "250", "500")):
    d = np.array([late[k][i] for k in ks]) - np.asarray(ref["accel_spec"][b])[ks]
    ok = np.percentile(np.abs(d), 95) <= 1.5
    bad += not ok
    print(f"   accel_spec[{b}]        median |diff| {np.median(np.abs(d)):8.4f}   95 % {np.percentile(np.abs(d), 95):8.4f}   "
          f"max {np.abs(d).max():8.3f}   {'ok' if ok else 'DIFFERENT'}")
if ref["baro_alt"] is not None:
    show("baro_alt", np.array([r.get("baro_alt", np.nan) for r in rows[:T]]), ref["baro_alt"], 0.5)
opened = [r["n"] for i, r in enumerate(rows) if i and r["stops"] > rows[i - 1]["stops"]]
print("   stops heard at", opened, "; phone states seen:", sorted({int(np.argmax(r["phone"])) for r in rows}))
sys.exit(1 if bad else 0)
