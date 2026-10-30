package com.transformsai.nutrifitai.data.repo;

import android.net.Uri;
import android.util.Xml;

import com.transformsai.nutrifitai.data.AppGraph;
import com.transformsai.nutrifitai.data.Result;
import com.transformsai.nutrifitai.data.db.entity.FoodEntity;
import com.transformsai.nutrifitai.data.db.entity.GoalEntity;
import com.transformsai.nutrifitai.data.db.entity.ImportBatchEntity;
import com.transformsai.nutrifitai.data.db.entity.MealEntryEntity;
import com.transformsai.nutrifitai.data.db.entity.MealItemEntity;
import com.transformsai.nutrifitai.data.db.entity.WaterEntity;
import com.transformsai.nutrifitai.data.db.entity.WeightEntity;
import com.transformsai.nutrifitai.io.ExportData;
import com.transformsai.nutrifitai.io.XmlCodec;
import com.transformsai.nutrifitai.sync.Collections;
import com.transformsai.nutrifitai.util.DateUtil;
import com.transformsai.nutrifitai.util.Ids;

import org.xmlpull.v1.XmlPullParser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

/** Builds export snapshots and applies validated XML imports with duplicate handling. */
public class ImportExportRepository {
    public static final long MAX_IMPORT_BYTES = 10L * 1024 * 1024;
    private final AppGraph g;

    public ImportExportRepository(AppGraph g) {
        this.g = g;
    }

    /** Blocking; called from the export foreground service thread. */
    public ExportData snapshot(String uid) {
        ExportData d = new ExportData();
        d.exportedAt = Instant.ofEpochMilli(DateUtil.nowMillis()).toString();
        for (MealEntryEntity e : g.db.diary().allEntries(uid)) {
            if (e.deleted) continue;
            List<MealItemEntity> items = g.db.diary().liveItemsOf(e.id);
            if (items.isEmpty()) continue;
            d.entries.add(e);
            d.items.put(e.id, items);
        }
        d.water.addAll(g.db.water().entries(uid, "0000-01-01", "9999-12-31"));
        d.weights.addAll(g.db.weights().range(uid, "0000-01-01", "9999-12-31"));
        d.goals.addAll(g.db.goals().all(uid));
        for (FoodEntity f : g.db.foods().search(uid, "", "", false, true, 10000)) d.foods.add(f);
        return d;
    }

    public static final class Preview {
        public ExportData data;
        public String fileName;
        public int duplicates;
        public int newRecords;
    }

    public void preview(Uri uri, String fileName, Result.Callback<Preview> cb) {
        g.runIo(() -> {
            try (InputStream in = g.context.getContentResolver().openInputStream(uri)) {
                if (in == null) throw new IllegalStateException("Cannot open file");
                byte[] bytes = readLimited(in);
                XmlPullParser p = Xml.newPullParser();
                p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
                p.setInput(new InputStreamReader(new java.io.ByteArrayInputStream(bytes), StandardCharsets.UTF_8));
                String uid = g.session.requireUid();
                ExportData d = XmlCodec.read(p, uid, DateUtil.nowMillis());
                Preview pv = new Preview();
                pv.data = d;
                pv.fileName = fileName;
                for (MealEntryEntity e : d.entries) count(pv, g.db.diary().entry(e.id) != null);
                for (WaterEntity w : d.water) count(pv, g.db.water().byId(w.id) != null);
                for (WeightEntity w : d.weights) count(pv, g.db.weights().byId(w.id) != null);
                for (GoalEntity x : d.goals) count(pv, g.db.goals().byId(x.id) != null);
                for (FoodEntity f : d.foods) count(pv, g.db.foods().byId(f.id) != null);
                g.postResult(cb, Result.ok(pv));
            } catch (Exception e) {
                g.postResult(cb, Result.fail("This file is not a valid NutriFit AI export: " + safe(e.getMessage())));
            }
        });
    }

    private static void count(Preview pv, boolean dup) {
        if (dup) pv.duplicates++;
        else pv.newRecords++;
    }

    private static String safe(String m) {
        if (m == null) return "unreadable file";
        return m.length() > 160 ? m.substring(0, 160) : m;
    }

    private static byte[] readLimited(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        long total = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > MAX_IMPORT_BYTES) throw new IllegalStateException("file larger than 10 MB");
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    /** @param replace true = overwrite records with the same id, false = skip them. */
    public void apply(Preview pv, boolean replace, Result.Callback<ImportBatchEntity> cb) {
        g.runIo(() -> {
            String uid = g.session.requireUid();
            ImportBatchEntity batch = new ImportBatchEntity();
            batch.id = Ids.newId();
            batch.uid = uid;
            batch.fileName = pv.fileName;
            batch.formatVersion = pv.data.formatVersion;
            batch.importedAt = DateUtil.nowMillis();
            ExportData d = pv.data;
            g.db.runInTransaction(() -> {
                for (MealEntryEntity e : d.entries) {
                    MealEntryEntity existing = g.db.diary().entry(e.id);
                    if (existing != null && !uid.equals(existing.uid)) { batch.skipped++; continue; }
                    if (existing != null && !replace) { batch.skipped++; continue; }
                    if (existing != null) { g.db.diary().deleteItemsOf(e.id); batch.replaced++; } else batch.added++;
                    g.db.diary().upsertEntry(e);
                    g.db.diary().upsertItems(d.items.get(e.id));
                    g.outbox.enqueue(uid, Collections.MEALS, e.id);
                }
                for (WaterEntity w : d.water) {
                    WaterEntity ex = g.db.water().byId(w.id);
                    if (!decide(ex != null, ex != null && !uid.equals(ex.uid), replace, batch)) continue;
                    g.db.water().upsert(w);
                    g.outbox.enqueue(uid, Collections.WATER, w.id);
                }
                for (WeightEntity w : d.weights) {
                    WeightEntity ex = g.db.weights().byId(w.id);
                    if (!decide(ex != null, ex != null && !uid.equals(ex.uid), replace, batch)) continue;
                    g.db.weights().upsert(w);
                    g.outbox.enqueue(uid, Collections.WEIGHTS, w.id);
                }
                for (GoalEntity x : d.goals) {
                    GoalEntity ex = g.db.goals().byId(x.id);
                    if (!decide(ex != null, ex != null && !uid.equals(ex.uid), replace, batch)) continue;
                    g.db.goals().upsert(x);
                    g.outbox.enqueue(uid, Collections.GOALS, x.id);
                }
                for (FoodEntity f : d.foods) {
                    FoodEntity ex = g.db.foods().byId(f.id);
                    if (!decide(ex != null, ex != null && !uid.equals(ex.ownerUid), replace, batch)) continue;
                    g.db.foods().upsertFood(f);
                    g.outbox.enqueue(uid, Collections.FOODS, f.id);
                }
                g.db.imports().insert(batch);
            });
            g.outbox.kick();
            g.postResult(cb, Result.ok(batch));
        });
    }

    /** @return true when the record should be written. Other accounts' ids are never overwritten. */
    private static boolean decide(boolean exists, boolean foreign, boolean replace, ImportBatchEntity b) {
        if (foreign || (exists && !replace)) {
            b.skipped++;
            return false;
        }
        if (exists) b.replaced++;
        else b.added++;
        return true;
    }
}
