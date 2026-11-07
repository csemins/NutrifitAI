"""Downloads freely licensed food photos from Wikimedia Commons for the curated catalog.

For every food we try a few English Wikipedia article titles and take the article's free lead
image (PageImages only returns free images), then record author + license for attribution.
Output: app/src/main/assets/food_images/<food_id>.webp (384x384 centre crop) and credits.json.
Foods without a suitable photo are listed in missing.txt and later generated (see generate_images.py).

Usage: python tools/assets/fetch_food_images.py [--only id1,id2] [--skip id1,id2]
"""
import io
import json
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "app/src/main/assets/food_images"
UA = "NutriFitAssetFetcher/1.0 (KUET student project; offline asset build)"
OK_LICENSE = re.compile(r"^(cc0|cc[- ]by(-sa)?[- ]?[0-9.]*|public domain|pd.*|cc-by.*)", re.I)

TITLES = {
    "cat_rice_white_cooked": ["Steamed rice", "Cooked rice"],
    "cat_rice_brown_cooked": ["Brown rice"],
    "cat_roti_atta": ["Chapati", "Roti"],
    "cat_paratha": ["Paratha"],
    "cat_luchi": ["Luchi", "Puri (food)"],
    "cat_bread_white": ["White bread"],
    "cat_oats_cooked": ["Porridge", "Oatmeal"],
    "cat_polao": ["Pulao", "Pilaf"],
    "cat_khichuri": ["Khichdi"],
    "cat_biryani_chicken": ["Biryani"],
    "cat_tehari_beef": ["Tehari"],
    "cat_dal_masoor": ["Dal"],
    "cat_lentils_boiled": ["Lentil"],
    "cat_chola_bhuna": ["Chana masala"],
    "cat_halim": ["Haleem"],
    "cat_chicken_curry": ["Chicken curry"],
    "cat_beef_curry": ["Kala bhuna", "Beef curry"],
    "cat_fish_curry_rui": ["Fish curry"],
    "cat_ilish_curry": ["Shorshe ilish", "Ilish"],
    "cat_shrimp_curry": ["Chingri malai curry", "Prawn curry"],
    "cat_egg_curry": ["Egg curry"],
    "cat_mixed_veg_bhaji": ["Labra (dish)"],
    "cat_shak_bhaji": ["Saag"],
    "cat_aloo_bhorta": ["Aloo bhorta", "Bhorta"],
    "cat_begun_bhaja": ["Begun bhaja"],
    "cat_potato_boiled": ["Boiled potato"],
    "cat_spinach_raw": ["Spinach"],
    "cat_tomato": ["Tomato"],
    "cat_cucumber": ["Cucumber"],
    "cat_carrot": ["Carrot"],
    "cat_egg_boiled": ["Boiled egg"],
    "cat_egg_fried": ["Fried egg"],
    "cat_chicken_breast": ["Chicken breast"],
    "cat_chicken_fry": ["Fried chicken"],
    "cat_paneer": ["Paneer"],
    "cat_milk_whole": ["Milk"],
    "cat_yogurt_plain": ["Yogurt"],
    "cat_mishti_doi": ["Mishti doi"],
    "cat_lassi": ["Lassi"],
    "cat_tea_milk": ["Masala chai", "Milk tea"],
    "cat_tea_black": ["Black tea"],
    "cat_coffee_milk": ["Caffè latte"],
    "cat_coconut_water": ["Coconut water"],
    "cat_cola": ["Cola"],
    "cat_banana": ["Banana"],
    "cat_mango": ["Mango"],
    "cat_guava": ["Guava"],
    "cat_papaya": ["Papaya"],
    "cat_jackfruit": ["Jackfruit"],
    "cat_lychee": ["Lychee"],
    "cat_apple": ["Apple"],
    "cat_orange": ["Orange (fruit)"],
    "cat_watermelon": ["Watermelon"],
    "cat_dates": ["Date (fruit)", "Date palm"],
    "cat_singara": ["Shingara", "Singara"],
    "cat_samosa": ["Samosa"],
    "cat_chotpoti": ["Chotpoti"],
    "cat_fuchka": ["Panipuri"],
    "cat_jhalmuri": ["Jhalmuri"],
    "cat_muri": ["Puffed rice"],
    "cat_chanachur": ["Chanachur", "Bombay mix"],
    "cat_biscuit_plain": ["Biscuit"],
    "cat_peanuts": ["Peanut"],
    "cat_almonds": ["Almond"],
    "cat_bhapa_pitha": ["Bhapa pitha"],
    "cat_rasgulla": ["Rasgulla"],
    "cat_payesh": ["Kheer"],
    "cat_mustard_oil": ["Mustard oil"],
    "cat_ghee": ["Ghee"],
    "cat_sugar": ["Sugar"],
}


def fetch(url, timeout=60):
    """GET with polite exponential backoff on HTTP 429 (Wikimedia rate limits bursts)."""
    for attempt in range(6):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA})
            with urllib.request.urlopen(req, timeout=timeout) as r:
                return r.read()
        except urllib.error.HTTPError as e:
            if e.code != 429 or attempt == 5:
                raise
            wait = int(e.headers.get("Retry-After") or 0) or 15 * (attempt + 1)
            print("  429, waiting", wait, "s")
            time.sleep(wait)


def get_json(url):
    return json.loads(fetch(url, 30))


def page_image(title):
    q = urllib.parse.urlencode({"action": "query", "titles": title, "prop": "pageimages", "piprop": "name",
                                "redirects": 1, "format": "json"})
    d = get_json("https://en.wikipedia.org/w/api.php?" + q)
    for p in d.get("query", {}).get("pages", {}).values():
        if "pageimage" in p:
            return p["pageimage"], p.get("title", title)
    return None, None


def strip_html(s):
    return re.sub(r"\s+", " ", re.sub(r"<[^>]+>", "", s or "")).strip()


def file_info(name):
    q = urllib.parse.urlencode({"action": "query", "titles": "File:" + name, "prop": "imageinfo",
                                "iiprop": "url|extmetadata|mime", "iiurlwidth": 640, "format": "json"})
    d = get_json("https://commons.wikimedia.org/w/api.php?" + q)
    for p in d.get("query", {}).get("pages", {}).values():
        ii = p.get("imageinfo")
        if ii:
            return ii[0]
    return None


def square(img, size=384):
    img = img.convert("RGB")
    w, h = img.size
    s = min(w, h)
    img = img.crop(((w - s) // 2, (h - s) // 2, (w - s) // 2 + s, (h - s) // 2 + s))
    return img.resize((size, size), Image.LANCZOS)


def main():
    only = skip = set()
    for a in sys.argv[1:]:
        if a.startswith("--only="):
            only = set(a[7:].split(","))
        if a.startswith("--skip="):
            skip = set(a[7:].split(","))
    OUT.mkdir(parents=True, exist_ok=True)
    credits_path = OUT / "credits.json"
    credits = json.loads(credits_path.read_text(encoding="utf-8")) if credits_path.exists() else {}
    missing = []
    for fid, titles in TITLES.items():
        if (only and fid not in only) or fid in skip:
            continue
        if not only and (OUT / (fid + ".webp")).exists():
            continue
        done = False
        for t in titles:
            try:
                name, article = page_image(t)
                if not name or name.lower().endswith((".svg", ".gif")):
                    continue
                info = file_info(name)
                if not info or info.get("mime") not in ("image/jpeg", "image/png", "image/webp"):
                    continue
                meta = info.get("extmetadata", {})
                lic = strip_html(meta.get("LicenseShortName", {}).get("value", ""))
                if not OK_LICENSE.match(lic):
                    print("  skip license", fid, lic)
                    continue
                img = Image.open(io.BytesIO(fetch(info["thumburl"])))
                square(img).save(OUT / (fid + ".webp"), "WEBP", quality=80, method=6)
                credits[fid] = {
                    "source": "Wikimedia Commons",
                    "file": name,
                    "article": article,
                    "author": strip_html(meta.get("Artist", {}).get("value", ""))[:120] or "Unknown",
                    "license": lic,
                    "license_url": meta.get("LicenseUrl", {}).get("value", ""),
                    "url": info.get("descriptionurl", ""),
                }
                print("ok", fid, "<-", name, "|", lic)
                done = True
                break
            except Exception as e:  # network or decode problem: try the next title
                print("  err", fid, t, type(e).__name__, e)
            finally:
                time.sleep(2.0)
        if not done:
            missing.append(fid)
            print("MISSING", fid)
    credits_path.write_text(json.dumps(credits, indent=1, ensure_ascii=False, sort_keys=True), encoding="utf-8")
    (ROOT / "tools/assets/missing.txt").write_text("\n".join(missing), encoding="utf-8")
    print("missing:", missing)


if __name__ == "__main__":
    main()
