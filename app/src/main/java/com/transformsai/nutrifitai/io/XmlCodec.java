package com.transformsai.nutrifitai.io;

import androidx.annotation.Nullable;

import com.transformsai.nutrifitai.data.db.entity.FoodEntity;
import com.transformsai.nutrifitai.data.db.entity.GoalEntity;
import com.transformsai.nutrifitai.data.db.entity.MealEntryEntity;
import com.transformsai.nutrifitai.data.db.entity.MealItemEntity;
import com.transformsai.nutrifitai.data.db.entity.WaterEntity;
import com.transformsai.nutrifitai.data.db.entity.WeightEntity;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Versioned NutriFit AI XML format (format-version="1"). Writing uses a small escaping writer;
 * reading uses XmlPullParser (android.util.Xml on device, kXML in JVM tests). Every value is
 * validated; invalid records are reported and skipped, never guessed.
 */
public final class XmlCodec {
    public static final int FORMAT_VERSION = 1;
    public static final String ROOT = "nutrifit-export";
    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_\\-]{1,64}");
    private static final int MAX_RECORDS = 50000;

    private XmlCodec() {
    }

    // ===================== writing =====================
    public static String write(ExportData d) {
        StringBuilder b = new StringBuilder(4096);
        b.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        b.append("<").append(ROOT).append(" format-version=\"").append(FORMAT_VERSION).append("\" exported-at=\"")
                .append(esc(d.exportedAt)).append("\">\n");
        b.append("  <goals>\n");
        for (GoalEntity g : d.goals) {
            b.append("    <goal");
            attr(b, "id", g.id); attr(b, "effective-date", g.effectiveDate); attr(b, "kcal", g.kcal);
            attr(b, "protein-g", g.proteinG); attr(b, "carbs-g", g.carbsG); attr(b, "fat-g", g.fatG);
            attr(b, "fibre-g", g.fibreG); attr(b, "water-ml", g.waterMl); attr(b, "method", g.method);
            attr(b, "created-at", g.createdAt);
            b.append("/>\n");
        }
        b.append("  </goals>\n  <custom-foods>\n");
        for (FoodEntity f : d.foods) {
            b.append("    <food");
            attr(b, "id", f.id); attr(b, "name", f.name); attr(b, "name-bn", f.nameBn); attr(b, "category", f.category);
            attr(b, "kcal-100g", f.kcal100); attr(b, "protein-100g", f.protein100); attr(b, "carbs-100g", f.carbs100);
            attr(b, "fat-100g", f.fat100); attr(b, "fibre-100g", f.fibre100); attr(b, "sugar-100g", f.sugar100);
            attr(b, "sodium-mg-100g", f.sodiumMg100); attr(b, "serving-label", f.servingLabel);
            attr(b, "serving-g", f.servingGrams); attr(b, "provenance", f.provenance);
            b.append("/>\n");
        }
        b.append("  </custom-foods>\n  <meals>\n");
        for (MealEntryEntity e : d.entries) {
            b.append("    <entry");
            attr(b, "id", e.id); attr(b, "date", e.date); attr(b, "meal-type", e.mealType); attr(b, "status", e.status);
            attr(b, "source", e.source); attr(b, "note", e.note); attr(b, "consumed-at", e.consumedAt);
            attr(b, "created-at", e.createdAt);
            b.append(">\n");
            List<MealItemEntity> items = d.items.get(e.id);
            if (items != null) {
                for (MealItemEntity i : items) {
                    b.append("      <item");
                    attr(b, "id", i.id); attr(b, "food-id", i.foodId); attr(b, "name", i.name); attr(b, "name-bn", i.nameBn);
                    attr(b, "servings", i.servings); attr(b, "serving-label", i.servingLabel); attr(b, "serving-g", i.servingGrams);
                    attr(b, "kcal-100g", i.kcal100); attr(b, "protein-100g", i.protein100); attr(b, "carbs-100g", i.carbs100);
                    attr(b, "fat-100g", i.fat100); attr(b, "fibre-100g", i.fibre100); attr(b, "sugar-100g", i.sugar100);
                    attr(b, "sodium-mg-100g", i.sodiumMg100); attr(b, "provenance", i.provenance);
                    b.append("/>\n");
                }
            }
            b.append("    </entry>\n");
        }
        b.append("  </meals>\n  <water>\n");
        for (WaterEntity w : d.water) {
            b.append("    <drink");
            attr(b, "id", w.id); attr(b, "date", w.date); attr(b, "logged-at", w.loggedAt); attr(b, "ml", w.ml);
            b.append("/>\n");
        }
        b.append("  </water>\n  <weights>\n");
        for (WeightEntity w : d.weights) {
            b.append("    <weight");
            attr(b, "id", w.id); attr(b, "date", w.date); attr(b, "logged-at", w.loggedAt); attr(b, "kg", w.kg);
            attr(b, "note", w.note);
            b.append("/>\n");
        }
        b.append("  </weights>\n</").append(ROOT).append(">\n");
        return b.toString();
    }

    private static void attr(StringBuilder b, String k, @Nullable Object v) {
        if (v == null) return;
        String s = v instanceof Double ? fmt((Double) v) : String.valueOf(v);
        b.append(' ').append(k).append("=\"").append(esc(s)).append('"');
    }

    private static String fmt(double d) {
        if (d == Math.rint(d)) return String.valueOf((long) d);
        return String.format(Locale.US, "%.4f", d).replaceAll("0+$", "");
    }

    public static String esc(@Nullable String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': b.append("&amp;"); break;
                case '<': b.append("&lt;"); break;
                case '>': b.append("&gt;"); break;
                case '"': b.append("&quot;"); break;
                case '\'': b.append("&apos;"); break;
                case '\n': b.append("&#10;"); break;
                default:
                    if (c >= 0x20 || c == '\t') b.append(c);
            }
        }
        return b.toString();
    }

    // ===================== reading =====================
    /**
     * @param p   a namespace-unaware pull parser already given the input
     * @param uid owner to assign; imported files never carry account identifiers
     */
    public static ExportData read(XmlPullParser p, String uid, long now) throws XmlPullParserException, IOException {
        ExportData d = new ExportData();
        int event = p.getEventType();
        while (event != XmlPullParser.START_TAG && event != XmlPullParser.END_DOCUMENT) event = p.next();
        if (event == XmlPullParser.END_DOCUMENT || !ROOT.equals(p.getName())) {
            throw new XmlPullParserException("Not a NutriFit AI export (missing <" + ROOT + ">)");
        }
        String version = p.getAttributeValue(null, "format-version");
        if (version == null) throw new XmlPullParserException("Missing format-version");
        int v;
        try {
            v = Integer.parseInt(version.trim());
        } catch (NumberFormatException e) {
            throw new XmlPullParserException("Invalid format-version");
        }
        if (v < 1 || v > FORMAT_VERSION) throw new XmlPullParserException("Unsupported format-version " + version);
        d.formatVersion = v;
        d.exportedAt = p.getAttributeValue(null, "exported-at");

        MealEntryEntity currentEntry = null;
        int records = 0;
        while ((event = p.next()) != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.END_TAG && "entry".equals(p.getName())) {
                currentEntry = null;
                continue;
            }
            if (event != XmlPullParser.START_TAG) continue;
            if (++records > MAX_RECORDS) throw new XmlPullParserException("File has too many records");
            String tag = p.getName();
            int line = p.getLineNumber();
            try {
                switch (tag) {
                    case "goal": d.goals.add(readGoal(p, uid, now)); break;
                    case "food": d.foods.add(readFood(p, uid, now)); break;
                    case "entry":
                        currentEntry = readEntry(p, uid, now);
                        d.entries.add(currentEntry);
                        d.items.put(currentEntry.id, new ArrayList<>());
                        break;
                    case "item":
                        if (currentEntry == null) throw new IllegalArgumentException("<item> outside <entry>");
                        MealItemEntity it = readItem(p, currentEntry.id, now);
                        it.position = d.items.get(currentEntry.id).size();
                        d.items.get(currentEntry.id).add(it);
                        break;
                    case "drink": d.water.add(readWater(p, uid, now)); break;
                    case "weight": d.weights.add(readWeight(p, uid, now)); break;
                    default: break; // container tags and unknown future tags are ignored
                }
            } catch (IllegalArgumentException ex) {
                d.errors.add("Line " + line + ": " + ex.getMessage());
                if ("entry".equals(tag)) currentEntry = null;
            }
        }
        // Entries whose items were all invalid are dropped.
        List<MealEntryEntity> keep = new ArrayList<>();
        for (MealEntryEntity e : d.entries) {
            if (d.items.get(e.id) != null && !d.items.get(e.id).isEmpty()) keep.add(e);
            else {
                d.items.remove(e.id);
                d.errors.add("Meal " + e.date + " " + e.mealType + " has no valid items and was skipped");
            }
        }
        d.entries.clear();
        d.entries.addAll(keep);
        return d;
    }

    private static GoalEntity readGoal(XmlPullParser p, String uid, long now) {
        GoalEntity g = new GoalEntity();
        g.id = id(p);
        g.uid = uid;
        g.effectiveDate = date(p, "effective-date");
        g.kcal = (int) num(p, "kcal", 800, 8000);
        g.proteinG = (int) num(p, "protein-g", 0, 500);
        g.carbsG = (int) num(p, "carbs-g", 0, 1000);
        g.fatG = (int) num(p, "fat-g", 0, 400);
        g.fibreG = (int) num(p, "fibre-g", 0, 150);
        g.waterMl = (int) num(p, "water-ml", 0, 8000);
        g.method = str(p, "method", 30, "imported");
        g.createdAt = (long) optNum(p, "created-at", 0, Long.MAX_VALUE, now);
        g.updatedAt = now;
        return g;
    }

    private static FoodEntity readFood(XmlPullParser p, String uid, long now) {
        FoodEntity f = new FoodEntity();
        f.id = id(p);
        f.ownerUid = uid;
        f.name = reqStr(p, "name", 80);
        f.nameBn = str(p, "name-bn", 80, null);
        f.category = str(p, "category", 30, "custom");
        f.kcal100 = num(p, "kcal-100g", 0, 950);
        f.protein100 = num(p, "protein-100g", 0, 100);
        f.carbs100 = num(p, "carbs-100g", 0, 100);
        f.fat100 = num(p, "fat-100g", 0, 100);
        f.fibre100 = optNullable(p, "fibre-100g", 0, 100);
        f.sugar100 = optNullable(p, "sugar-100g", 0, 100);
        f.sodiumMg100 = optNullable(p, "sodium-mg-100g", 0, 40000);
        f.servingLabel = str(p, "serving-label", 40, "100 g");
        f.servingGrams = optNum(p, "serving-g", 1, 3000, 100);
        f.provenance = FoodEntity.PROV_IMPORTED;
        f.sourceRef = "Imported from NutriFit AI XML";
        f.emoji = "🍽";
        f.aliases = f.name.toLowerCase(Locale.ROOT);
        f.updatedAt = now;
        return f;
    }

    private static MealEntryEntity readEntry(XmlPullParser p, String uid, long now) {
        MealEntryEntity e = new MealEntryEntity();
        e.id = id(p);
        e.uid = uid;
        e.date = date(p, "date");
        e.mealType = oneOf(p, "meal-type", "breakfast", "lunch", "dinner", "snack");
        e.status = oneOf(p, "status", MealEntryEntity.STATUS_CONSUMED, MealEntryEntity.STATUS_PLANNED);
        e.source = "import";
        e.note = str(p, "note", 300, null);
        double consumed = optNum(p, "consumed-at", 0, Long.MAX_VALUE, -1);
        e.consumedAt = consumed < 0 ? null : (long) consumed;
        e.createdAt = (long) optNum(p, "created-at", 0, Long.MAX_VALUE, now);
        e.updatedAt = now;
        return e;
    }

    private static MealItemEntity readItem(XmlPullParser p, String entryId, long now) {
        MealItemEntity i = new MealItemEntity();
        i.id = id(p);
        i.entryId = entryId;
        i.foodId = str(p, "food-id", 64, null);
        i.name = reqStr(p, "name", 80);
        i.nameBn = str(p, "name-bn", 80, null);
        i.servings = num(p, "servings", 0.01, 100);
        i.servingLabel = str(p, "serving-label", 40, "g");
        i.servingGrams = num(p, "serving-g", 0.1, 3000);
        if (i.servings * i.servingGrams > 5000) throw new IllegalArgumentException("portion too large");
        i.kcal100 = num(p, "kcal-100g", 0, 950);
        i.protein100 = num(p, "protein-100g", 0, 100);
        i.carbs100 = num(p, "carbs-100g", 0, 100);
        i.fat100 = num(p, "fat-100g", 0, 100);
        i.fibre100 = optNullable(p, "fibre-100g", 0, 100);
        i.sugar100 = optNullable(p, "sugar-100g", 0, 100);
        i.sodiumMg100 = optNullable(p, "sodium-mg-100g", 0, 40000);
        i.provenance = str(p, "provenance", 20, FoodEntity.PROV_IMPORTED);
        i.emoji = "🍽";
        i.updatedAt = now;
        return i;
    }

    private static WaterEntity readWater(XmlPullParser p, String uid, long now) {
        WaterEntity w = new WaterEntity();
        w.id = id(p);
        w.uid = uid;
        w.date = date(p, "date");
        w.loggedAt = (long) num(p, "logged-at", 0, Long.MAX_VALUE);
        w.ml = (int) num(p, "ml", 1, 5000);
        w.updatedAt = now;
        return w;
    }

    private static WeightEntity readWeight(XmlPullParser p, String uid, long now) {
        WeightEntity w = new WeightEntity();
        w.id = id(p);
        w.uid = uid;
        w.date = date(p, "date");
        w.loggedAt = (long) num(p, "logged-at", 0, Long.MAX_VALUE);
        w.kg = num(p, "kg", 20, 400);
        w.note = str(p, "note", 200, null);
        w.updatedAt = now;
        return w;
    }

    // ---------- attribute helpers (throw IllegalArgumentException with a readable message) ----------
    private static String id(XmlPullParser p) {
        String v = p.getAttributeValue(null, "id");
        if (v == null || !ID.matcher(v).matches()) throw new IllegalArgumentException("<" + p.getName() + "> has an invalid id");
        return v;
    }

    private static String date(XmlPullParser p, String k) {
        String v = p.getAttributeValue(null, k);
        if (v == null || !DATE.matcher(v).matches()) throw new IllegalArgumentException(k + " must be yyyy-MM-dd");
        try {
            java.time.LocalDate.parse(v);
        } catch (Exception e) {
            throw new IllegalArgumentException(k + " is not a real date");
        }
        return v;
    }

    private static String reqStr(XmlPullParser p, String k, int max) {
        String v = p.getAttributeValue(null, k);
        if (v == null || v.trim().isEmpty()) throw new IllegalArgumentException(k + " is required");
        v = v.trim();
        if (v.length() > max) throw new IllegalArgumentException(k + " is too long");
        return v;
    }

    @Nullable
    private static String str(XmlPullParser p, String k, int max, @Nullable String def) {
        String v = p.getAttributeValue(null, k);
        if (v == null || v.trim().isEmpty()) return def;
        v = v.trim();
        return v.length() > max ? v.substring(0, max) : v;
    }

    private static String oneOf(XmlPullParser p, String k, String... allowed) {
        String v = p.getAttributeValue(null, k);
        for (String a : allowed) if (a.equals(v)) return v;
        throw new IllegalArgumentException(k + " has an unsupported value");
    }

    private static double num(XmlPullParser p, String k, double min, double max) {
        String v = p.getAttributeValue(null, k);
        if (v == null) throw new IllegalArgumentException(k + " is required");
        return parseNum(k, v, min, max);
    }

    private static double optNum(XmlPullParser p, String k, double min, double max, double def) {
        String v = p.getAttributeValue(null, k);
        if (v == null || v.isEmpty()) return def;
        return parseNum(k, v, min, max);
    }

    @Nullable
    private static Double optNullable(XmlPullParser p, String k, double min, double max) {
        String v = p.getAttributeValue(null, k);
        if (v == null || v.isEmpty()) return null;
        return parseNum(k, v, min, max);
    }

    private static double parseNum(String k, String v, double min, double max) {
        double d;
        try {
            d = Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(k + " is not a number");
        }
        if (Double.isNaN(d) || Double.isInfinite(d) || d < min || d > max) {
            throw new IllegalArgumentException(k + " is out of range");
        }
        return d;
    }
}
