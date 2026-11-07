"""Builds app/src/main/assets/usda_sr_legacy.json.gz from USDA FoodData Central SR Legacy (public domain, CC0).

Input: FoodData_Central_sr_legacy_food_csv_2018-04.zip
  (https://fdc.nal.usda.gov/fdc-datasets/FoodData_Central_sr_legacy_food_csv_2018-04.zip)
Output rows (compact arrays, values per 100 g):
  [fdcId, description, category, kcal, protein, carbs, fat, fibre|null, sugar|null, sodium_mg|null,
   {micros}, [[serving label, grams], ...]]

Usage: python tools/assets/build_usda.py path/to/sr.zip
"""
import csv
import gzip
import io
import json
import sys
import zipfile
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "app/src/main/assets/usda_sr_legacy.json.gz"

# FDC nutrient ids
KCAL, PROT, FAT, CARB, FIBRE, SUGAR, SODIUM = 1008, 1003, 1004, 1005, 1079, 2000, 1093
MICROS = {1087: "calcium_mg", 1089: "iron_mg", 1092: "potassium_mg", 1090: "magnesium_mg", 1095: "zinc_mg",
          1162: "vitamin_c_mg", 1106: "vitamin_a_ug", 1114: "vitamin_d_ug", 1178: "vitamin_b12_ug",
          1177: "folate_ug", 1253: "cholesterol_mg", 1258: "saturated_fat_g"}

# USDA food group code -> NutriFit AI category (None = excluded from the app)
CATEGORY = {
    "0100": "dairy", "0200": "other", "0300": None, "0400": "fats", "0500": "protein", "0600": "other",
    "0700": "protein", "0800": "grains", "0900": "fruits", "1000": "protein", "1100": "vegetables",
    "1200": "snacks", "1300": "protein", "1400": "drinks", "1500": "protein", "1600": "lentils",
    "1700": "protein", "1800": "grains", "1900": "sweets", "2000": "grains", "2100": "meals",
    "2200": "meals", "2500": "snacks", "3500": None, "3600": "meals",
}


def rows(z, name):
    with z.open(next(n for n in z.namelist() if n.endswith("/" + name))) as f:
        yield from csv.DictReader(io.TextIOWrapper(f, encoding="utf-8"))


def r(v, d=1):
    return None if v is None else round(v, d)


def main(src):
    z = zipfile.ZipFile(src)
    cats = {row["id"]: row["code"] for row in rows(z, "food_category.csv")}
    foods = {}
    for row in rows(z, "food.csv"):
        code = cats.get(row["food_category_id"], "")
        cat = CATEGORY.get(code)
        if cat:
            foods[row["fdc_id"]] = {"name": row["description"].strip(), "cat": cat}
    wanted = {KCAL, PROT, FAT, CARB, FIBRE, SUGAR, SODIUM, *MICROS}
    values = defaultdict(dict)
    for row in rows(z, "food_nutrient.csv"):
        fid = row["fdc_id"]
        if fid in foods:
            nid = int(row["nutrient_id"])
            if nid in wanted and row["amount"] != "":
                values[fid][nid] = float(row["amount"])
    portions = defaultdict(list)
    for row in rows(z, "food_portion.csv"):
        fid = row["fdc_id"]
        if fid not in foods or not row["gram_weight"]:
            continue
        g = float(row["gram_weight"])
        amount = float(row["amount"] or 1)
        label = (row["modifier"] or row["portion_description"] or "").strip()
        if not label or g <= 0 or len(portions[fid]) >= 4:
            continue
        portions[fid].append([f"{amount:g} {label}"[:40], round(g, 1)])
    out, skipped = [], 0
    for fid, f in foods.items():
        v = values.get(fid, {})
        if KCAL not in v or PROT not in v or FAT not in v or CARB not in v:
            skipped += 1  # incomplete core macros: not useful for logging
            continue
        micros = {k: r(v[n], 2) for n, k in MICROS.items() if n in v}
        out.append([int(fid), f["name"], f["cat"], r(v[KCAL], 0), r(v[PROT]), r(v[CARB]), r(v[FAT]),
                    r(v.get(FIBRE)), r(v.get(SUGAR)), r(v.get(SODIUM), 0), micros, portions.get(fid, [])])
    out.sort(key=lambda x: x[1].lower())
    payload = {"dataset": "USDA FoodData Central SR Legacy (April 2018)", "license": "CC0 1.0 / public domain",
               "url": "https://fdc.nal.usda.gov/", "version": 1, "foods": out}
    data = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    OUT.write_bytes(gzip.compress(data, 9))
    print(f"{len(out)} foods ({skipped} skipped) -> {OUT} ({OUT.stat().st_size // 1024} KB gz, {len(data) // 1024} KB raw)")


if __name__ == "__main__":
    main(sys.argv[1])
