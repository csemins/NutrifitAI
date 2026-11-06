"""Builds the pose training set: app/src/main/assets/pose/pose_samples.csv

Sources (all labelled with exercise + state):
  * free-exercise-db photos (public domain, github.com/yuhonas/free-exercise-db): image 0/1 = start/peak
  * Google ML Kit fitness_pose_samples.csv (Apache-2.0): push-up and squat up/down landmarks
  * images generated for poses the database lacks (jumping jacks, high knees, wall sit)

Each image is run through the same MediaPipe BlazePose model the app uses (pose_landmarker_full.task).
States: rest = start position, peak = the counted end position, hold = a valid static hold.
The app fits each exercise's rest/peak profile from these samples at run time (see PoseTrainer.java).

Usage (needs `pip install mediapipe pillow`):
  python tools/pose/extract_landmarks.py <exdb_image_dir> <ml_kit_csv> <model.task>
"""
import csv
import sys
from pathlib import Path

import mediapipe as mp
from mediapipe.tasks import python as mp_tasks
from mediapipe.tasks.python import vision

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "app/src/main/assets/pose/pose_samples.csv"
GEN = ROOT / "tools/pose/generated"

# (db id, image index) -> (exercise, state)
DB = {
    "squat": [("Bodyweight_Squat", "rest", "peak"), ("Dumbbell_Squat", "rest", "peak"),
              ("Plie_Dumbbell_Squat", "rest", "peak"), ("Freehand_Jump_Squat", "peak", "rest")],
    "pushup": [("Pushups", "rest", "peak"), ("Push-Up_Wide", "rest", "peak"),
               ("Pushups_Close_and_Wide_Hand_Positions", "peak", "rest"), ("Push-Ups_With_Feet_Elevated", "rest", "peak"),
               ("Decline_Push-Up", "rest", "peak")],
    "lunge": [("Bodyweight_Walking_Lunge", "rest", "peak"), ("Dumbbell_Lunges", "rest", "peak"),
              ("Dumbbell_Rear_Lunge", "rest", "peak"), ("Split_Squats", "peak", "rest"),
              ("Split_Squat_with_Dumbbells", "rest", "peak")],
    "situp": [("Sit-Up", "rest", "peak"), ("Janda_Sit-Up", "rest", "peak"), ("3_4_Sit-Up", "rest", "peak")],
    "crunch": [("Crunches", "rest", "peak"), ("Crunch_-_Hands_Overhead", "rest", "peak")],
    "glute_bridge": [("Pelvic_Tilt_Into_Bridge", "rest", "peak"), ("Single_Leg_Glute_Bridge", "rest", "peak"),
                     ("Butt_Lift_Bridge", "auto", "auto")],
    "mountain_climber": [("Mountain_Climbers", "peak", "peak")],
    "shoulder_press": [("Dumbbell_Shoulder_Press", "rest", "peak"), ("Arnold_Dumbbell_Press", "rest", "peak")],
    "bicep_curl": [("Dumbbell_Bicep_Curl", "rest", "peak"), ("Hammer_Curls", "rest", "peak")],
    "lateral_raise": [("Side_Lateral_Raise", "rest", "peak"), ("One-Arm_Side_Laterals", "rest", "peak")],
    "plank": [("Plank", None, "hold")],
    "side_plank": [("Side_Bridge", None, "hold")],
    "jumping_jack": [("Star_Jump", None, "peak")],
}
GENERATED = {"jj_open": ("jumping_jack", "peak"), "jj_closed": ("jumping_jack", "rest"),
             "hk_up": ("high_knees", "peak"), "wallsit": ("wall_sit", "hold")}
MLKIT = {"pushups_up": ("pushup", "rest"), "pushups_down": ("pushup", "peak"),
         "squats_up": ("squat", "rest"), "squats_down": ("squat", "peak")}


def detector(model):
    opts = vision.PoseLandmarkerOptions(base_options=mp_tasks.BaseOptions(model_asset_path=str(model)),
                                        running_mode=vision.RunningMode.IMAGE, num_poses=1,
                                        min_pose_detection_confidence=0.4)
    return vision.PoseLandmarker.create_from_options(opts)


def landmarks(det, path):
    img = mp.Image.create_from_file(str(path))
    res = det.detect(img)
    if not res.pose_landmarks:
        return None
    w, h = img.width, img.height
    s = max(w, h)
    row = []
    for lm in res.pose_landmarks[0]:
        row += [round(lm.x * w / s, 5), round(lm.y * h / s, 5), round(lm.visibility or 0, 3)]
    return row


def hip_angle(row):
    """Mean shoulder-hip-knee angle, used to auto-label ambiguous bridge photos."""
    import math

    def p(i):
        return row[i * 3], row[i * 3 + 1]

    def ang(a, b, c):
        ax, ay = a[0] - b[0], a[1] - b[1]
        cx, cy = c[0] - b[0], c[1] - b[1]
        d = math.hypot(ax, ay) * math.hypot(cx, cy) or 1e-9
        return math.degrees(math.acos(max(-1, min(1, (ax * cx + ay * cy) / d))))

    return (ang(p(11), p(23), p(25)) + ang(p(12), p(24), p(26))) / 2


def main(exdb, mlkit_csv, model):
    det = detector(model)
    out = []
    exdb = Path(exdb)
    for ex, entries in DB.items():
        for db_id, s0, s1 in entries:
            rows = {}
            for n, st in ((0, s0), (1, s1)):
                if st is None:
                    continue
                f = exdb / f"{db_id}__{n}.jpg"
                if not f.exists():
                    print("missing", f.name)
                    continue
                r = landmarks(det, f)
                if r is None:
                    print("no pose", f.name)
                    continue
                rows[n] = (st, r)
            if s0 == "auto" and len(rows) == 2:  # the photo with the more open hip is the bridge peak
                a0, a1 = hip_angle(rows[0][1]), hip_angle(rows[1][1])
                rows = {0: ("peak" if a0 > a1 else "rest", rows[0][1]), 1: ("peak" if a1 >= a0 else "rest", rows[1][1])}
            for n, (st, r) in rows.items():
                out.append([ex, st, f"exdb:{db_id}/{n}"] + r)
    for f in sorted(GEN.glob("*.png")):
        key = f.stem.rsplit("_", 1)[0]
        if key in GENERATED:
            r = landmarks(det, f)
            if r is None:
                print("no pose", f.name)
                continue
            ex, st = GENERATED[key]
            out.append([ex, st, f"generated:{f.stem}"] + r)
    # second generated set: <exercise>__<state>__<n>.png
    for f in sorted((ROOT / "tools/pose/generated2").glob("*.png")):
        parts = f.stem.split("__")
        if len(parts) != 3:
            continue
        r = landmarks(det, f)
        if r is None:
            print("no pose", f.name)
            continue
        out.append([parts[0], parts[1], f"generated:{f.stem}"] + r)
    with open(mlkit_csv, encoding="utf-8") as fh:
        for row in csv.reader(fh):
            if len(row) < 2 + 99 or row[1] not in MLKIT:
                continue
            ex, st = MLKIT[row[1]]
            xs = [float(v) for v in row[2:2 + 99]]
            pts = [(xs[i * 3], xs[i * 3 + 1]) for i in range(33)]
            s = max(max(p[0] for p in pts), max(p[1] for p in pts)) or 1
            r = []
            for x, y in pts:
                r += [round(x / s, 5), round(y / s, 5), 1.0]
            out.append([ex, st, "mlkit:" + row[0][:40]] + r)
    OUT.parent.mkdir(parents=True, exist_ok=True)
    with open(OUT, "w", newline="", encoding="utf-8") as fh:
        w = csv.writer(fh)
        w.writerow(["exercise", "state", "source"] + [f"{a}{i}" for i in range(33) for a in ("x", "y", "v")])
        w.writerows(out)
    from collections import Counter
    print(len(out), "samples ->", OUT)
    for k, v in sorted(Counter((r[0], r[1]) for r in out).items()):
        print(" ", k, v)


if __name__ == "__main__":
    main(*sys.argv[1:4])
