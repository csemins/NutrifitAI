package com.transformsai.nutrifitai.ui.common;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.graphics.Bitmap;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.transformsai.nutrifitai.R;
import com.transformsai.nutrifitai.fitness.Muscles;
import com.transformsai.nutrifitai.ui.widget.PodiumView;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders every kind of share card off-screen (1080×1350) and keeps the PNGs in the app's cache
 * (cache/card-tests) so they can be pulled and checked by eye: crowded calorie card, muscle map, podium.
 */
@RunWith(AndroidJUnit4.class)
public class ShareCardRenderTest {
    private final Context c = InstrumentationRegistry.getInstrumentation().getTargetContext();

    private void save(String name, ShareCards.Card card) throws Exception {
        Bitmap[] out1 = new Bitmap[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                out1[0] = ShareCards.render(c, card, null, "@tester · Tracked with NutriFit AI")); // views need the UI thread
        Bitmap b = out1[0];
        assertEquals(1080, b.getWidth());
        assertEquals(1350, b.getHeight());
        File dir = new File(c.getCacheDir(), "card-tests");
        dir.mkdirs();
        try (FileOutputStream out = new FileOutputStream(new File(dir, name + ".png"))) {
            b.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
    }

    @Test
    public void progressCardWithBigNumberFits() throws Exception {
        ShareCards.Card c = new ShareCards.Card();
        c.kicker = "Month progress";
        c.title = "28 days of tracking";
        c.subtitle = "Oct 10 – Nov 7";
        c.big = "12,345";
        c.caption = "Hit my calorie range on 21 of 28 logged days, and a long caption to crowd the card";
        c.stat("12,345", "avg kcal").stat("128 g", "Protein").stat("2,450 ml", "Water").stat("28", "days logged");
        save("progress", c);
    }

    @Test
    public void workoutCardShowsMuscleMap() throws Exception {
        ShareCards.Card c = new ShareCards.Card();
        c.kicker = "Workout complete";
        c.title = "Full body burner";
        c.subtitle = "Mostly chest, triceps and quads";
        c.art = R.drawable.ic3d_fit;
        c.fitArt = true;
        Map<String, Float> m = new HashMap<>();
        List<String> all = new ArrayList<>(Muscles.all());
        for (int i = 0; i < Math.min(6, all.size()); i++) m.put(all.get(i), 1f - i * 0.15f);
        c.muscles = m;
        c.stat("32", "min").stat("148", "reps").stat("8", "exercises").stat("286", "kcal");
        save("workout", c);
    }

    @Test
    public void winnerCardShowsPodium() throws Exception {
        ShareCards.Card c = new ShareCards.Card();
        c.kicker = "👑 Champion";
        c.title = "Squat duel";
        c.subtitle = "Won Squat duel, 1st of 5";
        List<PodiumView.Entry> p = new ArrayList<>();
        p.add(new PodiumView.Entry("You", 340, true));
        p.add(new PodiumView.Entry("Sami Rahman", 290, false));
        p.add(new PodiumView.Entry("Nadia", 255, false));
        p.add(new PodiumView.Entry("Arif", 180, false));
        p.add(new PodiumView.Entry("Tanvir", 95, false));
        c.podium = p;
        save("podium", c);
    }
}
