"""Camera-only release (v3.1): every exercise the app offers must be trackable by the camera and need
no equipment. Moves without a trained camera model are archived (kept for old history only).

Camera models exist for: squat, jump_squat, pushup, jumping_jack, lunge, high_knees, situp, crunch,
glute_bridge, mountain_climber, plank, side_plank, wall_sit. Variants reuse a model via "track_as".

Run after tools/pose/derive_specs.py, which adds camera models for the remaining no-equipment moves.

Usage: python tools/assets/camera_only.py
"""
import json
from pathlib import Path

EX = Path(__file__).resolve().parents[2] / "app/src/main/assets/exercises/guided.json"
MODELS = {"squat", "jump_squat", "pushup", "jumping_jack", "lunge", "high_knees", "situp", "crunch",
          "glute_bridge", "mountain_climber", "plank", "side_plank", "wall_sit"}
VARIANTS = {"wide_pushup": "pushup", "diamond_pushup": "pushup", "split_squat": "lunge",
            "single_leg_bridge": "glute_bridge", "star_jump": "jumping_jack"}
WARMUP = {"jumping_jack", "high_knees", "star_jump"}


def main():
    g = json.load(open(EX, encoding="utf-8"))
    kept = 0
    for x in g["exercises"]:
        i = x["id"]
        if i in VARIANTS:
            x["track_as"] = VARIANTS[i]
        derived = "camera_spec" in x  # camera model from tools/pose/derive_specs.py
        camera = (i in MODELS or i in VARIANTS or derived) and x.get("needs") in (None, "Camera tracking")
        x["camera"] = camera
        if not camera:
            x["archived"] = True
            x.setdefault("needs", "Camera tracking")
        else:
            x.pop("archived", None)
            kept += 1
        if not derived:
            x["role"] = "warmup" if i in WARMUP else "main"
    g["version"] = 4
    json.dump(g, open(EX, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print("camera exercises:", kept, "archived:", len(g["exercises"]) - kept)


if __name__ == "__main__":
    main()
