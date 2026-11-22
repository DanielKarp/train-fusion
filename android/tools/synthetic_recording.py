"""A made-up recording in the recorder's format (docs/recorder.md), for checking the Kotlin
port's features against phone-sensor-fusion's (tools/check-port-synthetic). Nothing in it
comes from a ride: noise, a tone that follows a made-up speed, door chimes, a phone lying
still with a stretch of handling. Needs numpy and ffmpeg.

    python synthetic_recording.py <out dir> [seconds]
"""
import gzip
import json
import subprocess
import sys
from pathlib import Path

import numpy as np

out = Path(sys.argv[1])
T = int(sys.argv[2]) if len(sys.argv) > 2 else 150
out.mkdir(parents=True, exist_ok=True)
rng = np.random.default_rng(7)
t0 = 1000.0
FS = 48000

# --- audio: noise, a motor-like tone whose pitch follows a made-up speed, door chimes
n = T * FS
t = np.arange(n) / FS
speed = np.clip(np.interp(t, [0, 20, 40, 60, 80, 100, T], [0, 0, 20, 30, 0, 15, 25]), 0, None)
f0 = 30 * speed + 50
phase = 2 * np.pi * np.cumsum(f0) / FS
x = 0.01 * rng.standard_normal(n)
x += 0.02 * np.sin(phase) + 0.01 * np.sin(2 * phase)
for start, partials in ((82, (2200, 4400, 6600)), (95, (3520, 5280, 7040)), (30, (2200, 4400, 6600)), (45, (3520, 5280, 7040))):
    m = (t >= start) & (t < start + 1.5)
    for p in partials:
        x[m] += 0.05 * np.sin(2 * np.pi * p * t[m])
x = x.astype(np.float32)
audio_start = t0 + 0.05  # first frame captured 50 ms after the recording started
raw = out / "audio.f32"
raw.write_bytes(x.tobytes())
subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-f", "f32le", "-ar", str(FS), "-ac", "1", "-i", str(raw),
                "-c:a", "aac", "-b:a", "96k", str(out / "audio_000.m4a")], check=True)
raw.unlink()
with gzip.open(out / "audio_timing.csv.gz", "wt") as f:
    f.write("segment,frame,frames,uptime_s\n")
    for k in range(0, n, 4800):
        f.write(f"0,{k},4800,{audio_start + k / FS:.6f}\n")

# --- IMU: ~100 Hz with jitter; gravity on -z (iOS's sign), vibration lines, a stretch of handling
def stamps(rate):
    ts = t0 + np.arange(0, T, 1 / rate) + rng.uniform(-0.0005, 0.0005, int(np.ceil(T * rate)))[: len(np.arange(0, T, 1 / rate))]
    return np.sort(ts)

ta = stamps(100.0)
rel = ta - t0
v = np.interp(rel, [0, 20, 40, 60, 80, 100, T], [0, 0, 20, 30, 0, 15, 25])
wheel_hz = v / (np.pi * 0.84)  # a wheel of 0.84 m
acc = np.zeros((len(ta), 3))
acc[:, 2] = -1.0
acc[:, 0] += 0.01 * np.sin(2 * np.pi * np.cumsum(wheel_hz) / 100.0) + 0.003 * rng.standard_normal(len(ta))
acc[:, 1] += np.gradient(v, rel) / 9.80665 * 0.9 + 0.003 * rng.standard_normal(len(ta))
acc[:, 2] += 0.004 * np.sin(2 * np.pi * 7 * rel) + 0.002 * rng.standard_normal(len(ta))
handled = (rel > 110) & (rel < 125)
acc[handled] += 0.2 * rng.standard_normal((handled.sum(), 3))
with gzip.open(out / "accel.csv.gz", "wt") as f:
    f.write("uptime_s,x_g,y_g,z_g\n")
    for r in np.c_[ta, acc]:
        f.write(",".join(f"{c:.6f}" for c in r) + "\n")

tg = stamps(100.0)
relg = tg - t0
gyr = 0.002 * rng.standard_normal((len(tg), 3)) + [0.001, -0.002, 0.0005]
gyr[:, 2] += 0.01 * np.sin(2 * np.pi * relg / 40)
hg = (relg > 110) & (relg < 125)
gyr[hg] += 0.8 * rng.standard_normal((hg.sum(), 3))
with gzip.open(out / "gyro.csv.gz", "wt") as f:
    f.write("uptime_s,x_rad_s,y_rad_s,z_rad_s\n")
    for r in np.c_[tg, gyr]:
        f.write(",".join(f"{c:.6f}" for c in r) + "\n")

tb = t0 + np.arange(0.4, T, 1.25)
p = 101.2 - 0.0005 * (tb - t0) + 0.002 * rng.standard_normal(len(tb))
with gzip.open(out / "baro.csv.gz", "wt") as f:
    f.write("uptime_s,pressure_kpa,relative_altitude_m\n")
    for a, b in zip(tb, p):
        f.write(f"{a:.6f},{b:.6f},0.0\n")

# device motion: flat on its back, yawed 30 degrees; a quaternion iOS-style (either sense works downstream)
tf = stamps(100.0)
yaw = np.radians(30)
q = np.array([0.0, 0.0, np.sin(yaw / 2), np.cos(yaw / 2)])
with gzip.open(out / "device_motion.csv.gz", "wt") as f:
    f.write("uptime_s,qx,qy,qz,qw,rot_x_rad_s,rot_y_rad_s,rot_z_rad_s,gravity_x_g,gravity_y_g,gravity_z_g,"
            "user_accel_x_g,user_accel_y_g,user_accel_z_g,mag_x_ut,mag_y_ut,mag_z_ut,mag_accuracy,heading_deg\n")
    for a in tf:
        f.write(f"{a:.6f},{q[0]:.6f},{q[1]:.6f},{q[2]:.6f},{q[3]:.6f},0,0,0,0,0,-1,0,0,0,20,5,-40,2,30\n")

(out / "clock.csv").write_text("uptime_s,unix_s,ntp_offset_s,ntp_round_trip_s\n"
                                + "".join(f"{t0 + k:.6f},{1.7e9 + k:.6f},,\n" for k in range(0, T, 10))
                                + f"{t0 + 1:.6f},{1.7e9 + 1:.6f},0.012,0.030\n")
meta = {
    "format_version": 1, "id": out.name, "complete": True,
    "started_uptime_s": t0, "started_unix_s": 1.7e9, "ended_uptime_s": t0 + T, "ended_unix_s": 1.7e9 + T,
    "device_model": "synthetic", "app_version": "test", "ntp_server": "time.apple.com",
    "audio": {"codec": "AAC-LC in MPEG-4 (.m4a)", "input_latency_s": 0.0,
              "segments": [{"file": "audio_000.m4a", "sample_rate": 48000, "channels": 1, "frames": n,
                            "first_frame_uptime_s": audio_start}]},
    "motion": {}, "inferred": None, "ride": None,
}
(out / "meta.json").write_text(json.dumps(meta, indent=1))
print("wrote", out)
