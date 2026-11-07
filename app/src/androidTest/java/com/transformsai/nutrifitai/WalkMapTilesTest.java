package com.transformsai.nutrifitai;

import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.view.View;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.transformsai.nutrifitai.ui.steps.WalkMap;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.osmdroid.config.Configuration;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Renders the walk map off screen over Dhaka at the default zoom and at the deepest zoom, saving what it drew to
 * the app's external files folder (walkmap-*.png) so the tiles can be checked by eye. Tile requests are logged by
 * osmdroid's downloader debug flag.
 */
@RunWith(AndroidJUnit4.class)
public class WalkMapTilesTest {
    @Test
    public void rendersRealTilesAtDefaultAndDeepZoom() throws Exception {
        Context c = InstrumentationRegistry.getInstrumentation().getTargetContext();
        MapView[] holder = new MapView[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            MapView map = new MapView(c);
            WalkMap.attach(map);
            Configuration.getInstance().setDebugMapTileDownloader(true);
            Configuration.getInstance().setDebugTileProviders(true);
            map.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1600, View.MeasureSpec.EXACTLY));
            map.layout(0, 0, 1080, 1600);
            map.getController().setZoom(17.0);
            map.getController().setCenter(new GeoPoint(23.7509, 90.3890));
            holder[0] = map;
        });
        File a = render(c, holder[0], "walkmap-z17.png");
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> holder[0].getController().setZoom(19.5));
        File b = render(c, holder[0], "walkmap-z19.png");
        assertTrue(a.length() > 0 && b.length() > 0);
    }

    private static File render(Context c, MapView map, String name) throws Exception {
        Bitmap bmp = Bitmap.createBitmap(1080, 1600, Bitmap.Config.ARGB_8888);
        for (int i = 0; i < 20; i++) { // tiles arrive asynchronously; redraw until they are in
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> map.draw(new Canvas(bmp)));
            SystemClock.sleep(1000);
        }
        File out = new File(c.getExternalFilesDir(null), name);
        try (FileOutputStream os = new FileOutputStream(out)) {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, os);
        }
        return out;
    }
}
