"""Generates original images through the local OpenAI-compatible gateway (gpt-image-2).

Used only for (a) foods with no freely licensed photo, (b) exercise reference poses missing from
free-exercise-db, and (c) decorative art. The API key is read from server/.env at run time and is
never written anywhere else.

Usage: python tools/assets/generate_images.py jobs.json [--workers 3]
jobs.json: [{"out": "relative/or/absolute.png", "prompt": "...", "square": 384, "format": "webp"}]
"""
import base64
import io
import json
import re
import sys
import time
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
GATEWAY = "http://localhost:3000/v1/images/generations"


def api_key():
    for line in (ROOT / "server/.env").read_text(encoding="utf-8").splitlines():
        m = re.match(r"\s*NUTRIFIT_LLM_API_KEY\s*=\s*(.+?)\s*$", line)
        if m:
            return m.group(1)
    raise SystemExit("NUTRIFIT_LLM_API_KEY missing in server/.env")


def generate(job, key):
    out = Path(job["out"])
    if not out.is_absolute():
        out = ROOT / out
    if out.exists() and not job.get("force"):
        return f"skip {out.name}"
    body = json.dumps({"model": job.get("model", "gpt-image-2"), "prompt": job["prompt"], "n": 1,
                       "size": job.get("size", "1024x1024"), "response_format": "b64_json"}).encode()
    for attempt in range(3):
        try:
            req = urllib.request.Request(GATEWAY, data=body, headers={"Authorization": "Bearer " + key,
                                                                       "Content-Type": "application/json"})
            with urllib.request.urlopen(req, timeout=300) as r:
                data = json.loads(r.read())
            img = Image.open(io.BytesIO(base64.b64decode(data["data"][0]["b64_json"])))
            break
        except Exception as e:  # transient gateway errors
            if attempt == 2:
                return f"FAIL {out.name}: {type(e).__name__} {e}"
            time.sleep(10)
    out.parent.mkdir(parents=True, exist_ok=True)
    sq = job.get("square")
    if sq:
        img = img.convert("RGB")
        w, h = img.size
        s = min(w, h)
        img = img.crop(((w - s) // 2, (h - s) // 2, (w - s) // 2 + s, (h - s) // 2 + s)).resize((sq, sq), Image.LANCZOS)
    elif job.get("max"):
        img.thumbnail((job["max"], job["max"]), Image.LANCZOS)
    if job.get("black_to_alpha"):
        img = black_to_alpha(img)
    fmt = job.get("format", out.suffix.lstrip(".") or "png").upper()
    if fmt == "JPG":
        fmt = "JPEG"
    if fmt in ("JPEG",):
        img = img.convert("RGB")
    img.save(out, fmt, quality=job.get("quality", 82), **({"method": 6} if fmt == "WEBP" else {}))
    return f"ok {out.name}"


def black_to_alpha(img):
    """Turns a render on pure black into RGBA so it blends into dark cards without a box edge."""
    img = img.convert("RGB")
    px = img.load()
    w, h = img.size
    out = Image.new("RGBA", (w, h))
    po = out.load()
    for y in range(h):
        for x in range(w):
            r, g, b = px[x, y]
            m = max(r, g, b)
            a = 255 if m > 48 else int(m / 48 * 255)
            po[x, y] = (r, g, b, a)
    return out


def main():
    jobs = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
    workers = int(sys.argv[sys.argv.index("--workers") + 1]) if "--workers" in sys.argv else 3
    key = api_key()
    with ThreadPoolExecutor(workers) as ex:
        for msg in ex.map(lambda j: generate(j, key), jobs):
            print(msg, flush=True)


if __name__ == "__main__":
    main()
