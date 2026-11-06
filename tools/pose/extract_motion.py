"""Builds app/src/main/assets/exercises/motion.json.gz: start/end body poses for every library exercise.

free-exercise-db (public domain) has a start photo (0.jpg) and an end photo (1.jpg) for each exercise.
Each photo is run through MediaPipe BlazePose; only the 33 landmarks are kept (no image is shipped).
The app animates a neon figure between the two poses, so every exercise has a moving demonstration.

Usage: python tools/pose/extract_motion.py <exercises.json> <cache_dir> <pose_landmarker.task> [extra_samples.csv]
Output: {"<id>": [[x0,y0,v0,...x32,y32,v32], [...]]} quantised to 3 decimals, coordinates normalised
to the longer image side.
"""
import csv
import gzip
import json
import os
import sys
import time
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import mediapipe as mp
from mediapipe.tasks import python as mp_tasks
from mediapipe.tasks.python import vision

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "app/src/main/assets/exercises/motion.json.gz"
BASE = "https://raw.githubusercontent.com/yuhonas/free-exercise-db/main/exercises/"
UA = {"User-Agent": "NutriFitAssetFetcher/1.0"}


def fetch(job):
    url, dest = job
    if os.path.exists(dest) and os.path.getsize(dest) > 0:
        return
    for a in range(4):
        try:
            data = urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=60).read()
            open(dest, "wb").write(data)
            return
        except Exception:
            time.sleep(3 * (a + 1))


def main(ex_json, cache, model, extra=None):
    ex = json.loads(Path(ex_json).read_text(encoding="utf-8"))
    os.makedirs(cache, exist_ok=True)
    jobs = []
    for x in ex:
        for i, img in enumerate(x.get("images", [])[:2]):
            jobs.append((BASE + img, os.path.join(cache, f"{x['id']}__{i}.jpg")))
    with ThreadPoolExecutor(8) as pool:
        list(pool.map(fetch, jobs))
    print("downloaded", sum(1 for j in jobs if os.path.exists(j[1])), "/", len(jobs), flush=True)
    det = vision.PoseLandmarker.create_from_options(vision.PoseLandmarkerOptions(
        base_options=mp_tasks.BaseOptions(model_asset_path=str(model)),
        running_mode=vision.RunningMode.IMAGE, num_poses=1, min_pose_detection_confidence=0.35))
    out, missing = {}, 0
    for x in ex:
        poses = []
        for i in range(2):
            f = os.path.join(cache, f"{x['id']}__{i}.jpg")
            if not os.path.exists(f):
                continue
            try:
                img = mp.Image.create_from_file(f)
                r = det.detect(img)
            except Exception:
                continue
            if not r.pose_landmarks:
                continue
            s = max(img.width, img.height)
            row = []
            for lm in r.pose_landmarks[0]:
                row += [round(lm.x * img.width / s, 3), round(lm.y * img.height / s, 3), round(lm.visibility or 0, 2)]
            poses.append(row)
        if poses:
            out[x["id"]] = poses
        else:
            missing += 1
    # coached exercises that are not in the photo database (generated reference poses)
    if extra:
        best = {}
        with open(extra, encoding="utf-8") as fh:
            for row in csv.DictReader(fh):
                if not row["source"].startswith("generated:"):
                    continue
                key = (row["exercise"], row["state"])
                if key not in best:
                    best[key] = [round(float(row[f"{a}{i}"]), 3) for i in range(33) for a in ("x", "y", "v")]
        for ex_id in ("jumping_jack", "high_knees", "wall_sit"):
            rest = best.get((ex_id, "rest")) or best.get(("jumping_jack", "rest"))
            peak = best.get((ex_id, "peak")) or best.get((ex_id, "hold"))
            out["g:" + ex_id] = [p for p in (rest, peak) if p]
    data = json.dumps(out, separators=(",", ":")).encode()
    OUT.write_bytes(gzip.compress(data, 9))
    print(len(out), "exercises with motion,", missing, "without a detectable person ->", OUT,
          OUT.stat().st_size // 1024, "KB", flush=True)


if __name__ == "__main__":
    main(*sys.argv[1:5])
