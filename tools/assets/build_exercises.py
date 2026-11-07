"""Builds the exercise assets from free-exercise-db (public domain, github.com/yuhonas/free-exercise-db).

Outputs
  app/src/main/assets/exercises/library.json.gz   all 870+ exercises (names, muscles, steps, image paths)
  app/src/main/assets/exercises/guided.json       the coached exercises (camera-tracked or manual)
Demonstrations are motion only: looping clips (tools/assets/build_videos.py) or the animated figure
built from start/end poses (tools/pose/extract_motion.py). No still photos are shipped.

Usage: python tools/assets/build_exercises.py <exercises.json> <exdb_image_dir>
Library photos that are not bundled are loaded on demand from the dataset's GitHub raw URLs.
"""
import gzip
import json
import sys
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "app/src/main/assets"
GEN = ROOT / "tools/pose/generated"

# id, name, kind, camera, db id (or generated images), MET, default reps/seconds, muscles, cues
GUIDED = [
    ("jumping_jack", "Jumping jacks", "reps", True, ["gen:jj_closed_1", "gen:jj_open_1"], 8.0, 20, "Full body · cardio",
     ["Land softly on the balls of your feet", "Arms all the way overhead"]),
    ("squat", "Bodyweight squat", "reps", True, "Bodyweight_Squat", 5.0, 12, "Quads · glutes",
     ["Chest up, push your hips back", "Knees follow your toes"]),
    ("pushup", "Push-up", "reps", True, "Pushups", 8.0, 10, "Chest · triceps · core",
     ["Body in one straight line", "Lower your chest close to the floor"]),
    ("lunge", "Lunge", "reps", True, "Bodyweight_Walking_Lunge", 4.0, 12, "Quads · glutes",
     ["Step long, drop the back knee", "Front knee over the ankle"]),
    ("high_knees", "High knees", "reps", True, ["gen:jj_closed_1", "gen:hk_up_1"], 8.0, 24, "Cardio · hip flexors",
     ["Drive each knee to hip height", "Stay light and quick"]),
    ("situp", "Sit-up", "reps", True, "Sit-Up", 3.8, 12, "Abs",
     ["Exhale as you come up", "Lower with control"]),
    ("crunch", "Crunch", "reps", True, "Crunches", 3.8, 15, "Abs",
     ["Lift your shoulder blades", "Keep your neck relaxed"]),
    ("glute_bridge", "Glute bridge", "reps", True, "Butt_Lift_Bridge", 3.5, 12, "Glutes · hamstrings",
     ["Drive through your heels", "Squeeze at the top"]),
    ("mountain_climber", "Mountain climbers", "reps", True, "Mountain_Climbers", 8.0, 20, "Core · cardio",
     ["Hips level with your shoulders", "Knee towards your chest"]),
    ("jump_squat", "Jump squat", "reps", True, "Freehand_Jump_Squat", 8.0, 10, "Legs · power",
     ["Squat, then jump explosively", "Land softly into the next squat"]),
    ("shoulder_press", "Shoulder press", "reps", True, "Dumbbell_Shoulder_Press", 3.5, 12, "Shoulders · triceps",
     ["Use dumbbells or water bottles", "Press straight overhead"]),
    ("bicep_curl", "Biceps curl", "reps", True, "Dumbbell_Bicep_Curl", 3.5, 12, "Biceps",
     ["Elbows stay by your sides", "Lower slowly"]),
    ("lateral_raise", "Lateral raise", "reps", True, "Side_Lateral_Raise", 3.5, 12, "Shoulders",
     ["Lift to shoulder height", "Slight bend in the elbows"]),
    ("plank", "Plank", "hold", True, "Plank", 3.8, 30, "Core · shoulders",
     ["Elbows under shoulders", "Hips level, squeeze your glutes"]),
    ("side_plank", "Side plank", "hold", True, "Side_Bridge", 3.8, 20, "Obliques · core",
     ["Stack your feet", "Lift your hips high"]),
    ("wall_sit", "Wall sit", "hold", True, ["gen:wallsit_1", "gen:wallsit_3"], 4.0, 30, "Quads · glutes",
     ["Back flat on the wall", "Thighs parallel to the floor"]),
    ("superman", "Superman hold", "hold", False, "Superman", 3.0, 20, "Lower back · glutes",
     ["Lift arms and legs together", "Look at the floor"]),
    ("russian_twist", "Russian twist", "reps", False, "Russian_Twist", 3.8, 16, "Obliques",
     ["Lean back slightly", "Rotate from your ribs"]),
    ("flutter_kicks", "Flutter kicks", "reps", False, "Flutter_Kicks", 3.8, 20, "Lower abs",
     ["Lower back pressed down", "Small, quick kicks"]),
    ("arm_circles", "Arm circles", "hold", False, "Arm_Circles", 2.5, 30, "Shoulders · warm-up",
     ["Arms straight out to the sides", "Small circles, then bigger"]),
    ("inchworm", "Inchworm", "reps", False, "Inchworm", 3.5, 6, "Hamstrings · core",
     ["Walk your hands out to a plank", "Walk your feet back in"]),
    ("bench_dips", "Chair dips", "reps", False, "Bench_Dips", 3.8, 10, "Triceps",
     ["Use a sturdy chair", "Elbows point straight back"]),
    ("calf_raise", "Calf raises", "reps", False, "Standing_Dumbbell_Calf_Raise", 2.8, 15, "Calves",
     ["Rise onto your toes", "Lower slowly"]),
    ("reverse_crunch", "Reverse crunch", "reps", False, "Reverse_Crunch", 3.8, 12, "Lower abs",
     ["Curl your hips off the floor", "Control the way down"]),
    ("glute_kickback", "Glute kickback", "reps", False, "Glute_Kickback", 3.5, 12, "Glutes",
     ["Kick back and up", "Keep your back flat"]),
    ("star_jump", "Star jumps", "reps", False, "Star_Jump", 8.0, 10, "Full body · power",
     ["Explode into a star shape", "Land softly"]),
    ("burpee", "Burpee", "reps", False, [], 8.0, 8, "Full body · cardio",
     ["Hands down, jump back to a plank", "Jump in and explode up"]),
]


def save_img(src, out, width=480):
    img = Image.open(src).convert("RGB")
    img.thumbnail((width, width), Image.LANCZOS)
    img.save(out, "WEBP", quality=78, method=6)


def main(ex_json, img_dir):
    lib = json.loads(Path(ex_json).read_text(encoding="utf-8"))
    by_id = {x["id"]: x for x in lib}
    out_dir = ASSETS / "exercises"
    out_dir.mkdir(parents=True, exist_ok=True)

    library = []
    for x in lib:
        library.append({"id": x["id"], "name": x["name"], "level": x.get("level"), "equipment": x.get("equipment") or "body only",
                        "category": x.get("category"), "force": x.get("force"), "mechanic": x.get("mechanic"),
                        "primary": x.get("primaryMuscles", []), "secondary": x.get("secondaryMuscles", []),
                        "steps": x.get("instructions", []), "images": len(x.get("images", []))})
    data = json.dumps({"source": "free-exercise-db (public domain, The Unlicense)",
                       "url": "https://github.com/yuhonas/free-exercise-db",
                       "image_base": "https://raw.githubusercontent.com/yuhonas/free-exercise-db/main/exercises/",
                       "exercises": library}, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    (out_dir / "library.json.gz").write_bytes(gzip.compress(data, 9))

    credits_file = ASSETS / "exercise_videos/credits.json"
    video_credits = json.loads(credits_file.read_text(encoding="utf-8")) if credits_file.exists() else {}
    guided = []
    for gid, name, kind, camera, db, met, default, muscles, cues in GUIDED:
        db_id = db if isinstance(db, str) else None
        steps = by_id.get(db_id, {}).get("instructions", []) if db_id else []
        g = {"id": gid, "name": name, "kind": kind, "camera": camera, "db": db_id, "met": met,
             "default": default, "muscles": muscles, "cues": cues, "steps": steps[:5], "images": []}
        if (ASSETS / "exercise_videos" / f"{gid}.webp").exists():
            g["video"] = f"exercise_videos/{gid}.webp"
            g["video_credit"] = video_credits.get(gid, "")
        guided.append(g)
    (out_dir / "guided.json").write_text(json.dumps({"version": 1, "exercises": guided}, ensure_ascii=False, indent=1),
                                         encoding="utf-8")
    print(len(library), "library,", len(guided), "guided,", sum(1 for g in guided if "video" in g), "with video")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
