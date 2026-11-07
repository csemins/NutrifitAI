"""Instrument-free release of the exercise data (v3).

- library.json.gz: only exercises that need no equipment (no bars, benches, balls or weights).
- motion.json.gz: animation poses only for the remaining exercises.
- guided.json: demo videos removed (animations only), equipment exercises archived, muscle targets
  (0..1 intensity per muscle) for the muscle map, and extra bodyweight moves, warm-ups and stretches.

Usage: python tools/assets/slim_exercises.py
"""
import gzip
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
EX = ROOT / "app/src/main/assets/exercises"

NEEDS_GEAR = ["Pull-Up", "Pullup", "Chin-Up", "Hanging", "Bench", "Exercise_Ball", "Dumbbell", "V-Bar",
              "Inverted_Row", "Towel", "Step-up", "Incline", "Decline", "Dips_-_Triceps", "Feet_Elevated",
              "Body-Up", "Scapular", "Kneeling_Arm_Drill", "Linear_", "Wall_Drill", "Trail_Running"]

# free-exercise-db muscle names -> muscle-map ids
MUSCLE = {"abdominals": "abs", "quadriceps": "quads", "lower back": "lower_back", "middle back": "middle_back",
          "hamstrings": "hamstrings", "glutes": "glutes", "shoulders": "shoulders", "calves": "calves",
          "triceps": "triceps", "chest": "chest", "lats": "lats", "adductors": "adductors", "abductors": "abductors",
          "traps": "traps", "biceps": "biceps", "forearms": "forearms", "neck": "neck"}

TARGETS = {
    "jumping_jack": {"shoulders": .6, "calves": .7, "quads": .5, "glutes": .4, "abductors": .5, "adductors": .4},
    "squat": {"quads": 1, "glutes": .9, "hamstrings": .4, "adductors": .4, "lower_back": .3, "calves": .2},
    "pushup": {"chest": 1, "triceps": .8, "shoulders": .6, "abs": .4},
    "lunge": {"quads": 1, "glutes": .9, "hamstrings": .5, "calves": .3, "adductors": .3},
    "high_knees": {"hip_flexors": .9, "quads": .6, "calves": .6, "abs": .4},
    "situp": {"abs": 1, "hip_flexors": .6, "obliques": .4},
    "crunch": {"abs": 1, "obliques": .4},
    "glute_bridge": {"glutes": 1, "hamstrings": .7, "lower_back": .4},
    "mountain_climber": {"abs": .9, "hip_flexors": .7, "shoulders": .6, "quads": .4, "chest": .3},
    "jump_squat": {"quads": 1, "glutes": .9, "calves": .7, "hamstrings": .4},
    "plank": {"abs": 1, "shoulders": .6, "lower_back": .4, "glutes": .3, "quads": .2},
    "side_plank": {"obliques": 1, "abs": .6, "shoulders": .5, "abductors": .4},
    "wall_sit": {"quads": 1, "glutes": .6, "calves": .2},
    "superman": {"lower_back": 1, "glutes": .7, "middle_back": .5, "hamstrings": .4},
    "russian_twist": {"obliques": 1, "abs": .7},
    "flutter_kicks": {"abs": .9, "hip_flexors": .8},
    "arm_circles": {"shoulders": 1, "traps": .4},
    "inchworm": {"hamstrings": .8, "abs": .6, "shoulders": .6, "chest": .3},
    "calf_raise": {"calves": 1},
    "reverse_crunch": {"abs": 1, "hip_flexors": .4},
    "glute_kickback": {"glutes": 1, "hamstrings": .5},
    "star_jump": {"quads": .8, "glutes": .7, "calves": .8, "shoulders": .5},
    "burpee": {"quads": .8, "chest": .7, "shoulders": .6, "abs": .6, "triceps": .5, "glutes": .5, "calves": .5},
    "shoulder_press": {"shoulders": 1, "triceps": .6, "traps": .3},
    "bicep_curl": {"biceps": 1, "forearms": .5},
    "lateral_raise": {"shoulders": 1, "traps": .4},
    "bench_dips": {"triceps": 1, "chest": .4, "shoulders": .4},
}
ARCHIVE = {"shoulder_press": "Dumbbells", "bicep_curl": "Dumbbells", "lateral_raise": "Dumbbells", "bench_dips": "Bench"}

NEW = [
    dict(id="wide_pushup", name="Wide push-up", kind="reps", db="Push-Up_Wide", met=3.8, default=10, track_as="pushup",
         muscles="Chest · shoulders", cues=["Hands wider than shoulders", "Body in one straight line"],
         targets={"chest": 1, "shoulders": .7, "triceps": .5, "abs": .4}),
    dict(id="diamond_pushup", name="Close-grip push-up", kind="reps", db="Push-Ups_-_Close_Triceps_Position", met=3.8,
         default=8, track_as="pushup", muscles="Triceps · chest", cues=["Elbows stay close to your ribs", "Lower slowly"],
         targets={"triceps": 1, "chest": .7, "shoulders": .5, "abs": .3}),
    dict(id="split_squat", name="Split squat", kind="reps", db="Split_Squats", met=4.0, default=10, track_as="lunge",
         muscles="Quads · glutes", cues=["Front knee over the ankle", "Torso tall"],
         targets={"quads": 1, "glutes": .8, "hamstrings": .5, "calves": .3}),
    dict(id="single_leg_bridge", name="Single-leg glute bridge", kind="reps", db="Single_Leg_Glute_Bridge", met=3.5,
         default=10, track_as="glute_bridge", muscles="Glutes · hamstrings", cues=["Hips level", "Squeeze at the top"],
         targets={"glutes": 1, "hamstrings": .7, "lower_back": .3}),
    dict(id="butt_kicks", name="Butt kicks", kind="reps", db="Single_Leg_Butt_Kick", met=8.0, default=30,
         muscles="Hamstrings · cardio", cues=["Quick light feet", "Heels to glutes"],
         targets={"hamstrings": .8, "quads": .5, "calves": .6}),
    dict(id="dead_bug", name="Dead bug", kind="reps", db="Dead_Bug", met=3.0, default=12,
         muscles="Deep core", cues=["Lower back pressed to the floor", "Move slowly, opposite arm and leg"],
         targets={"abs": 1, "hip_flexors": .4, "obliques": .4}),
    dict(id="side_leg_raise", name="Side-lying leg raise", kind="reps", db="Side_Leg_Raises", met=3.0, default=12,
         muscles="Outer hips", cues=["Keep the top leg straight", "Do not roll backwards"],
         targets={"abductors": 1, "glutes": .6, "obliques": .3}),
    dict(id="elbow_to_knee", name="Elbow to knee crunch", kind="reps", db="Elbow_to_Knee", met=3.8, default=16,
         muscles="Obliques · abs", cues=["Rotate from the ribs", "Do not pull on your neck"],
         targets={"obliques": 1, "abs": .8, "hip_flexors": .3}),
    dict(id="tuck_jump", name="Tuck jump", kind="reps", db="Knee_Tuck_Jump", met=8.0, default=8,
         muscles="Legs · power", cues=["Knees up to the chest", "Land softly"],
         targets={"quads": .9, "calves": .8, "glutes": .7, "hamstrings": .5, "abs": .4}),
    dict(id="scissor_kick", name="Scissor kicks", kind="reps", db="Scissor_Kick", met=3.8, default=20,
         muscles="Lower abs", cues=["Lower back down", "Small controlled kicks"],
         targets={"abs": 1, "hip_flexors": .8, "quads": .3}),
    dict(id="childs_pose", name="Child's pose", kind="hold", db="Childs_Pose", met=2.0, default=30,
         muscles="Lower back · stretch", cues=["Breathe slowly", "Sink the hips back"],
         targets={"lower_back": .6, "lats": .4, "glutes": .3}),
    dict(id="cat_cow", name="Cat-cow stretch", kind="hold", db="Cat_Stretch", met=2.0, default=30,
         muscles="Spine · mobility", cues=["Move with your breath", "Round, then arch gently"],
         targets={"lower_back": .6, "middle_back": .5, "abs": .2}),
    dict(id="hamstring_stretch", name="Hamstring stretch", kind="hold", db="Hamstring_Stretch", met=2.0, default=30,
         muscles="Hamstrings · stretch", cues=["Hinge at the hips", "No bouncing"],
         targets={"hamstrings": .7, "calves": .3, "lower_back": .2}),
    dict(id="hip_flexor_stretch", name="Kneeling hip-flexor stretch", kind="hold", db="Kneeling_Hip_Flexor", met=2.0,
         default=30, muscles="Hip flexors · stretch", cues=["Tuck the pelvis under", "Tall torso"],
         targets={"hip_flexors": .7, "quads": .4}),
    dict(id="worlds_greatest", name="World's greatest stretch", kind="reps", db="Worlds_Greatest_Stretch", met=2.5,
         default=6, muscles="Full-body mobility", cues=["Step long", "Rotate and reach to the ceiling"],
         targets={"hamstrings": .5, "hip_flexors": .5, "glutes": .4, "middle_back": .4, "shoulders": .3}),
]

# warm-up / main / cool-down role used by the AI planner
ROLE = {"arm_circles": "warmup", "jumping_jack": "warmup", "high_knees": "warmup", "butt_kicks": "warmup",
        "inchworm": "warmup", "worlds_greatest": "warmup", "childs_pose": "cooldown", "cat_cow": "cooldown",
        "hamstring_stretch": "cooldown", "hip_flexor_stretch": "cooldown"}


def gear(eid):
    return any(k.lower() in eid.lower() for k in NEEDS_GEAR)


def main():
    lib = json.loads(gzip.open(EX / "library.json.gz").read())
    keep = [e for e in lib["exercises"] if e.get("equipment") in ("body only", None) and not gear(e["id"])]
    lib["exercises"] = keep
    lib["note"] = "Instrument-free subset of free-exercise-db (public domain)"
    by_id = {e["id"]: e for e in keep}
    full_lib = {e["id"]: e for e in json.loads(gzip.open(EX / "library.json.gz").read())["exercises"]}

    g = json.load(open(EX / "guided.json", encoding="utf-8"))
    out = []
    for x in g["exercises"]:
        x.pop("video", None)
        x.pop("video_credit", None)
        x["images"] = []
        x["targets"] = TARGETS.get(x["id"], x.get("targets", {}))
        if x["id"] in ARCHIVE:
            x["archived"] = True
            x["needs"] = ARCHIVE[x["id"]]
        if x["id"] == "calf_raise":
            x["name"] = "Calf raise"
            x["db"] = "Standing_Gastrocnemius_Calf_Stretch"
        x["role"] = ROLE.get(x["id"], "main")
        x["muscles"] = x["muscles"].replace("�", "·")
        out.append(x)
    have = {x["id"] for x in out}
    for n in NEW:
        if n["id"] in have:
            continue
        src = full_lib[n["db"]]
        x = dict(n)
        x["camera"] = "track_as" in n
        x["steps"] = src["steps"][:6]
        x["images"] = []
        x["role"] = ROLE.get(n["id"], "main")
        out.append(x)
    g["exercises"] = out
    g["version"] = 3
    json.dump(g, open(EX / "guided.json", "w", encoding="utf-8"), ensure_ascii=False, indent=1)

    keys = set(by_id) | {x["db"] for x in out if x.get("db")}
    motion = json.loads(gzip.open(EX / "motion.json.gz").read())
    motion = {k: v for k, v in motion.items() if k in keys or k.startswith("g:")}
    with gzip.open(EX / "motion.json.gz", "wt", encoding="utf-8") as f:
        json.dump(motion, f, separators=(",", ":"))
    # library keeps referenced gear-free entries only (guided 'db' ids for archived ones stay resolvable via motion)
    with gzip.open(EX / "library.json.gz", "wt", encoding="utf-8") as f:
        json.dump(lib, f, separators=(",", ":"), ensure_ascii=False)
    print("library", len(keep), "guided", len(out), "archived", sum(1 for x in out if x.get("archived")),
          "motion", len(motion), "missing motion", [x["id"] for x in out if x.get("db") not in motion and "g:" + x["id"] not in motion])


if __name__ == "__main__" and "--burpee" not in __import__("sys").argv:
    main()


def burpee_motion():
    """Burpee has no photo pair: stand -> squat -> plank -> squat keyframes from the squat and push-up poses,
    scaled to the same torso length and standing on the same floor line."""
    import math
    motion = json.loads(gzip.open(EX / "motion.json.gz").read())
    full = json.loads(gzip.open(ROOT / "tools/pose/motion_full_backup.json.gz").read()) if (ROOT / "tools/pose/motion_full_backup.json.gz").exists() else motion
    src = [full["Bodyweight_Squat"][0], full["Bodyweight_Squat"][1], full["Pushups"][0]]

    def norm(p):
        xs, ys = p[0::3], p[1::3]
        sx, sy = (xs[11] + xs[12]) / 2, (ys[11] + ys[12]) / 2
        hx, hy = (xs[23] + xs[24]) / 2, (ys[23] + ys[24]) / 2
        t = math.hypot(sx - hx, sy - hy) or 1
        floor = max(ys[27], ys[28], ys[31], ys[32])
        out = []
        for i in range(33):
            out += [round((xs[i] - hx) / t * 0.25 + 0.5, 4), round((p[i * 3 + 1] - floor) / t * 0.25 + 0.9, 4), p[i * 3 + 2]]
        return out

    stand, squat, plank = (norm(p) for p in src)
    motion["g:burpee"] = [stand, squat, plank, squat]
    with gzip.open(EX / "motion.json.gz", "wt", encoding="utf-8") as f:
        json.dump(motion, f, separators=(",", ":"))
    print("burpee keyframes", len(motion["g:burpee"]))


if __name__ == "__main__" and "--burpee" in __import__("sys").argv:
    burpee_motion()
