package com.transformsai.nutrifitai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.transformsai.nutrifitai.data.db.entity.GoalEntity;
import com.transformsai.nutrifitai.data.db.entity.MealEntryEntity;
import com.transformsai.nutrifitai.data.db.entity.MealItemEntity;
import com.transformsai.nutrifitai.data.db.entity.WaterEntity;
import com.transformsai.nutrifitai.data.db.entity.WeightEntity;
import com.transformsai.nutrifitai.io.CsvWriter;
import com.transformsai.nutrifitai.io.ExportData;
import com.transformsai.nutrifitai.io.XmlCodec;

import org.junit.Test;
import org.kxml2.io.KXmlParser;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.StringReader;
import java.util.Collections;

public class XmlCodecTest {
    static ExportData sample() {
        ExportData d = new ExportData();
        d.exportedAt = "2026-11-05T10:00:00Z";
        MealEntryEntity e = new MealEntryEntity();
        e.id = "e1";
        e.uid = "old-owner";
        e.date = "2026-11-05";
        e.mealType = "lunch";
        e.status = "consumed";
        e.note = "Tom & \"Jerry\" <lunch>";
        e.consumedAt = 1L;
        e.createdAt = 1L;
        MealItemEntity i = new MealItemEntity();
        i.id = "i1";
        i.entryId = "e1";
        i.name = "ভাত & dal";
        i.servings = 1.5;
        i.servingLabel = "1 cup";
        i.servingGrams = 158;
        i.kcal100 = 130;
        i.protein100 = 2.7;
        i.carbs100 = 28.2;
        i.fat100 = 0.3;
        i.fibre100 = null;
        d.entries.add(e);
        d.items.put("e1", Collections.singletonList(i));
        WaterEntity w = new WaterEntity();
        w.id = "w1";
        w.date = "2026-11-05";
        w.loggedAt = 5;
        w.ml = 300;
        d.water.add(w);
        WeightEntity kg = new WeightEntity();
        kg.id = "k1";
        kg.date = "2026-11-05";
        kg.loggedAt = 5;
        kg.kg = 70.4;
        d.weights.add(kg);
        GoalEntity g = new GoalEntity();
        g.id = "g1";
        g.effectiveDate = "2026-11-01";
        g.kcal = 2000;
        g.proteinG = 100;
        g.carbsG = 250;
        g.fatG = 67;
        g.fibreG = 28;
        g.waterMl = 2300;
        g.method = "manual";
        d.goals.add(g);
        return d;
    }

    static ExportData read(String xml) throws Exception {
        XmlPullParser p = new KXmlParser();
        p.setInput(new StringReader(xml));
        return XmlCodec.read(p, "me", 42);
    }

    @Test
    public void roundTripPreservesValuesAndEscaping() throws Exception {
        String xml = XmlCodec.write(sample());
        ExportData back = read(xml);
        assertEquals(1, back.entries.size());
        MealEntryEntity e = back.entries.get(0);
        assertEquals("me", e.uid);                                // ownership reassigned to importer
        assertEquals("Tom & \"Jerry\" <lunch>", e.note);
        MealItemEntity i = back.items.get("e1").get(0);
        assertEquals("ভাত & dal", i.name);
        assertEquals(1.5, i.servings, 1e-9);
        assertNull(i.fibre100);                                   // unknown stays unknown
        assertEquals(300, back.water.get(0).ml);
        assertEquals(70.4, back.weights.get(0).kg, 1e-9);
        assertEquals(2000, back.goals.get(0).kcal);
        assertTrue(back.errors.isEmpty());
    }

    @Test
    public void invalidRecordsAreReportedAndSkipped() throws Exception {
        String xml = "<?xml version=\"1.0\"?><nutrifit-export format-version=\"1\">"
                + "<water><drink id=\"a\" date=\"2026-11-05\" logged-at=\"1\" ml=\"300\"/>"
                + "<drink id=\"b\" date=\"2026-13-40\" logged-at=\"1\" ml=\"300\"/>"
                + "<drink id=\"c\" date=\"2026-11-05\" logged-at=\"1\" ml=\"99999\"/></water>"
                + "<meals><entry id=\"e\" date=\"2026-11-05\" meal-type=\"lunch\" status=\"consumed\">"
                + "<item id=\"x\" name=\"Rice\" servings=\"1\" serving-g=\"100\" kcal-100g=\"9000\" protein-100g=\"1\" carbs-100g=\"1\" fat-100g=\"1\"/>"
                + "</entry></meals></nutrifit-export>";
        ExportData d = read(xml);
        assertEquals(1, d.water.size());
        assertEquals(0, d.entries.size());                        // its only item was invalid
        assertTrue(d.errors.size() >= 3);
    }

    @Test
    public void rejectsWrongRootAndFutureVersion() throws Exception {
        try {
            read("<?xml version=\"1.0\"?><other/>");
            fail();
        } catch (XmlPullParserException expected) {
            // ok
        }
        try {
            read("<?xml version=\"1.0\"?><nutrifit-export format-version=\"99\"/>");
            fail();
        } catch (XmlPullParserException expected) {
            // ok
        }
    }

    @Test
    public void csvEscapesAndBlocksFormulaInjection() {
        assertEquals("\"a,b\"", CsvWriter.cell("a,b"));
        assertEquals("\"say \"\"hi\"\"\"", CsvWriter.cell("say \"hi\""));
        assertEquals("'=SUM(A1)", CsvWriter.cell("=SUM(A1)"));
        assertEquals("-5", CsvWriter.cell("-5"));
        String csv = CsvWriter.write(sample());
        assertTrue(csv.startsWith("type,date,meal"));
        assertTrue(csv.contains("meal,2026-11-05,lunch,consumed"));
        assertTrue(csv.contains("water,2026-11-05"));
    }
}
