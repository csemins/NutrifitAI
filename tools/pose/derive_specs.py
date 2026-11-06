"""Derives camera models for moves that have no labelled pose samples.

For each move, the start and end body poses (33 BlazePose landmarks extracted with MediaPipe from the
free-exercise-db photos; assets/exercises/motion.json.gz) are measured with the same view-tolerant
features the app uses (fitness/pose/PoseFeatures.java, ported below). The chosen features' start and
end values become the move's rest/peak (reps) or centre (holds). The result is written into
guided.json as "camera_spec" and loaded by ExerciseLibrary -> ExerciseSpec.register().

Usage: python tools/pose/derive_specs.py [--table]
"""
import gzip
import json
import math
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
EX = ROOT / "app/src/main/assets/exercises"
VIS = 0.45
L_SH, R_SH, L_EL, R_EL, L_WR, R_WR = 11, 12, 13, 14, 15, 16
L_HIP, R_HIP, L_KN, R_KN, L_AN, R_AN = 23, 24, 25, 26, 27, 28
L_HEEL, R_HEEL, L_TOE, R_TOE = 29, 30, 31, 32


class Pose:
    def __init__(self, arr):
        self.x = arr[0::3]
        self.y = arr[1::3]
        self.v = arr[2::3]

    def vis(self, *idx):
        return all(self.v[i] >= VIS for i in idx)


def angle(p, a, b, c):
    if not p.vis(a, b, c):
        return math.nan
    ax, ay, cx, cy = p.x[a] - p.x[b], p.y[a] - p.y[b], p.x[c] - p.x[b], p.y[c] - p.y[b]
    d = math.hypot(ax, ay) * math.hypot(cx, cy)
    if d < 1e-9:
        return math.nan
    return math.degrees(math.acos(max(-1, min(1, (ax * cx + ay * cy) / d))))


def mn(a, b):
    return b if math.isnan(a) else a if math.isnan(b) else min(a, b)


def mean(a, b):
    return b if math.isnan(a) else a if math.isnan(b) else (a + b) / 2


def torso(p):
    if not p.vis(L_SH, R_SH, L_HIP, R_HIP):
        return math.nan
    sx, sy = (p.x[L_SH] + p.x[R_SH]) / 2, (p.y[L_SH] + p.y[R_SH]) / 2
    hx, hy = (p.x[L_HIP] + p.x[R_HIP]) / 2, (p.y[L_HIP] + p.y[R_HIP]) / 2
    t = math.hypot(sx - hx, sy - hy)
    return math.nan if t < 1e-4 else t


def mid_visible(p, a, b):
    va, vb = p.v[a] >= VIS, p.v[b] >= VIS
    if va and vb:
        return (p.x[a] + p.x[b]) / 2, (p.y[a] + p.y[b]) / 2
    i = a if va else b
    return p.x[i], p.y[i]


def feature(p, f):
    if f == "KNEE":
        return mn(angle(p, L_HIP, L_KN, L_AN), angle(p, R_HIP, R_KN, R_AN))
    if f == "KNEE_MEAN":
        return mean(angle(p, L_HIP, L_KN, L_AN), angle(p, R_HIP, R_KN, R_AN))
    if f == "HIP":
        return mean(angle(p, L_SH, L_HIP, L_KN), angle(p, R_SH, R_HIP, R_KN))
    if f == "HIP_MIN":
        return mn(angle(p, L_SH, L_HIP, L_KN), angle(p, R_SH, R_HIP, R_KN))
    if f == "ELBOW":
        return mean(angle(p, L_SH, L_EL, L_WR), angle(p, R_SH, R_EL, R_WR))
    if f == "ELBOW_MIN":
        return mn(angle(p, L_SH, L_EL, L_WR), angle(p, R_SH, R_EL, R_WR))
    if f == "ARM_RAISE":
        return mean(angle(p, L_HIP, L_SH, L_EL), angle(p, R_HIP, R_SH, R_EL))
    if f == "LEG_SPLIT":
        a, b = angle(p, L_SH, L_HIP, L_KN), angle(p, R_SH, R_HIP, R_KN)
        return math.nan if math.isnan(a) or math.isnan(b) else abs(a - b)
    t = torso(p)
    if f == "HEEL_RISE":
        if math.isnan(t):
            return math.nan
        v = [(p.y[toe] - p.y[heel]) / t for heel, toe in ((L_HEEL, L_TOE), (R_HEEL, R_TOE)) if p.vis(heel, toe)]
        return sum(v) / len(v) if v else math.nan
    if f == "WRIST_HEIGHT":
        if math.isnan(t) or not p.vis(L_WR, R_WR):
            return math.nan
        return ((p.y[L_SH] + p.y[R_SH]) / 2 - (p.y[L_WR] + p.y[R_WR]) / 2) / t
    if f == "LEG_SPREAD":
        if math.isnan(t) or not p.vis(L_AN, R_AN):
            return math.nan
        return math.hypot(p.x[L_AN] - p.x[R_AN], p.y[L_AN] - p.y[R_AN]) / t
    if f == "KNEE_LIFT":
        if math.isnan(t) or not p.vis(L_KN, R_KN):
            return math.nan
        return max((p.y[L_HIP] - p.y[L_KN]) / t, (p.y[R_HIP] - p.y[R_KN]) / t)
    if f == "BODY_TILT":
        if not p.vis(L_SH, R_SH) or not (p.vis(L_AN) or p.vis(R_AN)):
            return math.nan
        sx, sy = (p.x[L_SH] + p.x[R_SH]) / 2, (p.y[L_SH] + p.y[R_SH]) / 2
        ax, ay = mid_visible(p, L_AN, R_AN)
        return math.degrees(math.atan2(abs(sy - ay), abs(sx - ax) + 1e-9))
    if f == "TORSO_TILT":
        if not p.vis(L_SH, R_SH, L_HIP, R_HIP):
            return math.nan
        sx, sy = (p.x[L_SH] + p.x[R_SH]) / 2, (p.y[L_SH] + p.y[R_SH]) / 2
        hx, hy = (p.x[L_HIP] + p.x[R_HIP]) / 2, (p.y[L_HIP] + p.y[R_HIP]) / 2
        return math.degrees(math.atan2(abs(sx - hx), abs(sy - hy) + 1e-9))
    return math.nan


ALL = ["KNEE", "KNEE_MEAN", "HIP", "HIP_MIN", "ELBOW", "ELBOW_MIN", "ARM_RAISE", "WRIST_HEIGHT",
       "LEG_SPREAD", "KNEE_LIFT", "BODY_TILT", "TORSO_TILT"]
RATIO = {"WRIST_HEIGHT", "LEG_SPREAD", "KNEE_LIFT", "HEEL_RISE"}

# Hand-chosen, biomechanically meaningful features per move (the numbers come from the photos).
# reps: features that change between the start and end of the movement; holds: what defines the position.
PLAN = {
    # values measured on the start/end photos
    "russian_twist": dict(kind="reps", f=["ARM_RAISE", "WRIST_HEIGHT"], view="side", cue="Rotate further, hands towards the floor"),
    "inchworm": dict(kind="reps", f=["HIP"], view="side", cue="Walk your hands all the way out"),
    "reverse_crunch": dict(kind="reps", f=["HIP_MIN"], view="side", cue="Bring your knees closer to your chest"),
    "glute_kickback": dict(kind="reps", f=["HIP", "KNEE_LIFT"], view="side", cue="Kick the heel higher, squeeze the glute"),
    "burpee": dict(kind="reps", f=["BODY_TILT"], view="side", cue="Jump the feet all the way back to a plank"),
    "dead_bug": dict(kind="reps", f=["HIP", "KNEE_MEAN"], view="side", cue="Extend the leg further, lower back stays down"),
    "side_leg_raise": dict(kind="reps", f=["LEG_SPREAD"], view="front", cue="Lift the top leg higher"),
    "tuck_jump": dict(kind="reps", f=["KNEE_MEAN", "HIP_MIN"], swap=True, view="front", cue="Knees up to your chest"),
    "worlds_greatest": dict(kind="reps", f=["HIP_MIN", "ARM_RAISE"], view="side", cue="Step longer and reach higher"),
    "arm_circles": dict(kind="hold", f=["ARM_RAISE", "ELBOW"], view="front", cue="Arms straight out to the sides"),
    "superman": dict(kind="hold", f=["HIP", "BODY_TILT"], tol={"HIP": 14.0}, view="side", cue="Lift arms and legs off the floor"),
    "childs_pose": dict(kind="hold", f=["HIP_MIN", "KNEE_MEAN"], view="side", cue="Sit your hips back to your heels"),
    "cat_cow": dict(kind="hold", f=["HIP", "KNEE_MEAN"], view="side", cue="Hands under shoulders, knees under hips"),
    "hamstring_stretch": dict(kind="hold", f=["HIP_MIN", "KNEE_MEAN"], view="side", cue="Hinge forward with straight knees"),
    "hip_flexor_stretch": dict(kind="hold", f=["KNEE", "TORSO_TILT"], view="side", cue="Tall torso, tuck the pelvis"),
    # the photos do not show the movement clearly: rule-based thresholds (stated as such)
    "butt_kicks": dict(kind="reps", rule={"KNEE": (168, 75)}, gate=(40, 90), view="side", cue="Heels all the way to your glutes"),
    "flutter_kicks": dict(kind="reps", rule={"LEG_SPLIT": (4, 24)}, gate=(0, 55), view="side", cue="Bigger kicks, legs straight"),
    "scissor_kick": dict(kind="reps", rule={"LEG_SPLIT": (4, 26)}, gate=(0, 55), view="side", cue="Keep the scissor motion big"),
    "elbow_to_knee": dict(kind="reps", rule={"LEG_SPLIT": (8, 45)}, gate=(0, 55), view="side", cue="Drive the knee all the way in"),
    "calf_raise": dict(kind="reps", rule={"HEEL_RISE": (0.0, 0.1)}, gate=(40, 90), view="side", cue="Rise all the way onto your toes"),
}
ROLE = {"arm_circles": "warmup", "butt_kicks": "warmup", "worlds_greatest": "warmup", "inchworm": "warmup",
        "childs_pose": "cooldown", "cat_cow": "cooldown", "hamstring_stretch": "cooldown", "hip_flexor_stretch": "cooldown"}
MIN_CHANGE = {"angle": 20.0, "ratio": 0.18}


def main():
    motion = json.loads(gzip.open(EX / "motion.json.gz").read())
    g = json.load(open(EX / "guided.json", encoding="utf-8"))
    table = "--table" in sys.argv
    ok = 0
    for x in g["exercises"]:
        plan = PLAN.get(x["id"])
        if plan is None:
            continue
        key = "g:" + x["id"] if "g:" + x["id"] in motion else x.get("db")
        poses = [Pose(p) for p in motion.get(key, [])]
        if len(poses) < 2:
            print("no poses", x["id"])
            continue
        start, end = poses[0], poses[1] if x["id"] != "burpee" else poses[2]  # burpee: stand -> plank
        if plan.get("swap"):
            start, end = end, start  # the photos are in the opposite order (peak first)
        if table:
            row = {f: (round(feature(start, f), 2), round(feature(end, f), 2)) for f in ALL}
            print(x["id"], row)
        feats, rest, peak, centre, tol = [], [], [], [], []
        for f, (r0, p0) in plan.get("rule", {}).items():
            feats.append(f)
            rest.append(r0)
            peak.append(p0)
        for f in plan.get("f", []):
            a, b = feature(start, f), feature(end, f)
            if math.isnan(a) or math.isnan(b):
                continue
            if plan["kind"] == "reps":
                need = MIN_CHANGE["ratio" if f in RATIO else "angle"]
                if abs(b - a) < need:
                    continue  # this feature barely moves in the photos: not a reliable counter
                feats.append(f)
                rest.append(round(a, 2))
                peak.append(round(b, 2))
            else:
                feats.append(f)
                centre.append(round(b, 2))
                tol.append(plan.get("tol", {}).get(f, 0.35 if f in RATIO else 28.0))
        if not feats:
            print("NOT TRACKABLE", x["id"])
            x.pop("camera_spec", None)
            continue
        # gate: floor moves only count while lying/kneeling, standing moves only while upright
        tilts = [feature(start, "BODY_TILT"), feature(end, "BODY_TILT")]
        gate = None
        if "gate" in plan:
            gate = {"feature": "BODY_TILT", "min": plan["gate"][0], "max": plan["gate"][1]}
        elif all(not math.isnan(t) and t < 40 for t in tilts):
            gate = {"feature": "BODY_TILT", "min": 0, "max": 55}
        elif all(not math.isnan(t) and t > 55 for t in tilts) and x["id"] not in ("burpee", "inchworm"):
            gate = {"feature": "BODY_TILT", "min": 40, "max": 90}
        spec = {"kind": plan["kind"], "features": feats, "view": plan["view"], "cue": plan["cue"],
                "source": "rule-based thresholds" if "rule" in plan else "free-exercise-db start/end photos (MediaPipe landmarks)"}
        if plan["kind"] == "reps":
            spec["rest"], spec["peak"] = rest, peak
        else:
            spec["centre"], spec["tolerance"] = centre, tol
        if gate:
            spec["gate"] = gate
        x["camera_spec"] = spec
        x["role"] = ROLE.get(x["id"], "main")
        x["camera"] = True
        x.pop("archived", None)
        x.pop("needs", None)
        ok += 1
        print("tracked", x["id"], feats, spec.get("rest", spec.get("centre")), spec.get("peak", ""))
    g["version"] = 5
    json.dump(g, open(EX / "guided.json", "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print("camera models derived:", ok)


if __name__ == "__main__":
    main()
