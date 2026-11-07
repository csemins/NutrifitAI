package com.transformsai.nutrifitai.ui.steps;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.transformsai.nutrifitai.BuildConfig;
import com.transformsai.nutrifitai.R;

import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase;
import org.osmdroid.tileprovider.tilesource.XYTileSource;
import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.CustomZoomButtonsController;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.CopyrightOverlay;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Polygon;
import org.osmdroid.views.overlay.Polyline;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * OpenStreetMap map for walks: dark CARTO basemap (OpenStreetMap data), a glowing lime route line, start and
 * "you are here" markers and a GPS accuracy circle. Tiles are cached in the app's own folders.
 */
public final class WalkMap {
    /**
     * CARTO "Dark Matter": OpenStreetMap data in a dark style that matches the app. CARTO basemaps need an API key
     * (BuildConfig.CARTO_KEY, from local.properties), sent as {@code ?key=} on every tile request; without it the
     * service returns a "key required" placeholder instead of the map. Uses the high-resolution (@2x, 512 px)
     * tiles so street names stay sharp on dense phone screens. The source name is also the cache key: it changed
     * from "CartoDark" so placeholder tiles saved by builds without a key are never shown again. CARTO serves raster
     * tiles up to zoom 18 only (19+ answer 403), so the source stops at 18 and deeper zooms enlarge zoom-18 tiles.
     */
    private static final OnlineTileSourceBase DARK = new XYTileSource("CartoDarkHd18", 2, 18, 512, "@2x.png", new String[]{
            "https://a.basemaps.cartocdn.com/dark_all/", "https://b.basemaps.cartocdn.com/dark_all/",
            "https://c.basemaps.cartocdn.com/dark_all/", "https://d.basemaps.cartocdn.com/dark_all/"},
            "© OpenStreetMap contributors © CARTO") {
        @Override
        public String getTileURLString(long index) {
            String url = super.getTileURLString(index);
            return BuildConfig.CARTO_KEY.isEmpty() ? url : url + "?key=" + BuildConfig.CARTO_KEY;
        }
    };
    /** Bump when cached tiles must be thrown away (e.g. they were saved without a valid key). */
    private static final int TILE_CACHE_VERSION = 3;
    private static final int LIME = 0xFFB8F15A, LIME_GLOW = 0x55B8F15A;

    private final MapView map;
    private final Polyline glow, line;
    private final Marker start, me;
    private final Polygon accuracy;

    private WalkMap(MapView map) {
        this.map = map;
        Context c = map.getContext();
        float d = c.getResources().getDisplayMetrics().density;
        glow = polyline(LIME_GLOW, 16 * d);
        line = polyline(LIME, 6 * d);
        accuracy = new Polygon(map);
        accuracy.getFillPaint().setColor(0x22B8F15A);
        accuracy.getOutlinePaint().setColor(0x66B8F15A);
        accuracy.getOutlinePaint().setStrokeWidth(1.5f * d);
        accuracy.setVisible(false);
        start = marker(R.drawable.map_start);
        me = marker(R.drawable.map_me);
        start.setVisible(false);
        me.setVisible(false);
        map.getOverlays().add(accuracy);
        map.getOverlays().add(glow);
        map.getOverlays().add(line);
        map.getOverlays().add(start);
        map.getOverlays().add(me);
        map.getOverlays().add(new CopyrightOverlay(c));
    }

    private static boolean initialised;

    /**
     * Points osmdroid at the app's own folders and drops old tile caches. Must run before a MapView is inflated
     * (the view opens its tile cache when it is created), so activities call it before setContentView.
     */
    public static synchronized void init(Context context) {
        if (initialised) return;
        initialised = true;
        Context c = context.getApplicationContext();
        org.osmdroid.config.IConfigurationProvider conf = Configuration.getInstance();
        conf.load(c, c.getSharedPreferences("osmdroid", Context.MODE_PRIVATE));
        conf.setUserAgentValue(BuildConfig.APPLICATION_ID);
        File base = new File(c.getFilesDir(), "osmdroid");
        File tiles = new File(c.getCacheDir(), "osmdroid-tiles");
        android.content.SharedPreferences sp = c.getSharedPreferences("walk_map", Context.MODE_PRIVATE);
        if (sp.getInt("tile_cache", 0) < TILE_CACHE_VERSION) {
            // tiles saved by older builds (keyless "key required" placeholders), wherever osmdroid put them
            deleteTree(tiles);
            deleteTree(new File(base, "tiles"));
            deleteTree(conf.getOsmdroidTileCache());
            File ext = c.getExternalFilesDir(null);
            if (ext != null) deleteTree(new File(ext, "osmdroid"));
            sp.edit().putInt("tile_cache", TILE_CACHE_VERSION).apply();
        }
        conf.setOsmdroidBasePath(base);
        conf.setOsmdroidTileCache(tiles);
    }

    /** Styles {@code map} for walks ({@link #init} has run, or runs now as a fallback). */
    public static WalkMap attach(MapView map) {
        Context c = map.getContext().getApplicationContext();
        init(c);
        map.setTileSource(DARK);
        map.setMultiTouchControls(true);
        // 512 px retina tiles drawn at the same map scale as 256 dp tiles: half the stretching, crisp labels
        map.setTilesScaledToDpi(false);
        map.setTilesScaleFactor(Math.max(1f, c.getResources().getDisplayMetrics().density / 2f));
        map.getZoomController().setVisibility(CustomZoomButtonsController.Visibility.NEVER);
        map.setMinZoomLevel(3.0);
        map.setMaxZoomLevel(18.0); // CARTO serves raster tiles up to zoom 18
        map.setBackgroundColor(Color.BLACK);
        map.getOverlayManager().getTilesOverlay().setLoadingBackgroundColor(0xFF0B0D0B);
        map.getOverlayManager().getTilesOverlay().setLoadingLineColor(0xFF0B0D0B);
        map.getController().setZoom(17.0);
        return new WalkMap(map);
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private Polyline polyline(int color, float width) {
        Polyline p = new Polyline(map);
        Paint paint = p.getOutlinePaint();
        paint.setColor(color);
        paint.setStrokeWidth(width);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setAntiAlias(true);
        p.setInfoWindow(null);
        return p;
    }

    private Marker marker(int drawable) {
        Marker m = new Marker(map);
        Drawable icon = ContextCompat.getDrawable(map.getContext(), drawable);
        m.setIcon(icon);
        m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
        m.setInfoWindow(null);
        m.setPanToView(false);
        return m;
    }

    public static List<GeoPoint> points(List<double[]> route) {
        List<GeoPoint> out = new ArrayList<>(route.size());
        for (double[] p : route) out.add(new GeoPoint(p[0], p[1]));
        return out;
    }

    /** Draws the route and, when known, the current position with its accuracy. */
    public void show(List<double[]> route, double lat, double lon, float accuracyM) {
        List<GeoPoint> pts = points(route);
        glow.setPoints(pts);
        line.setPoints(pts);
        if (!pts.isEmpty()) {
            start.setPosition(pts.get(0));
            start.setVisible(true);
        }
        if (!Double.isNaN(lat)) {
            GeoPoint here = new GeoPoint(lat, lon);
            me.setPosition(here);
            me.setVisible(true);
            if (!Float.isNaN(accuracyM) && accuracyM > 0) {
                accuracy.setPoints(Polygon.pointsAsCircle(here, accuracyM));
                accuracy.setVisible(true);
            }
        }
        map.invalidate();
    }

    /** A finished walk: route, start and an end marker instead of "you are here". */
    public void showFinished(List<double[]> route) {
        show(route, Double.NaN, Double.NaN, Float.NaN);
        if (!route.isEmpty()) {
            double[] last = route.get(route.size() - 1);
            me.setIcon(ContextCompat.getDrawable(map.getContext(), R.drawable.map_end));
            me.setPosition(new GeoPoint(last[0], last[1]));
            me.setVisible(true);
        }
    }

    public void centre(double lat, double lon, boolean animate) {
        if (Double.isNaN(lat)) return;
        GeoPoint p = new GeoPoint(lat, lon);
        if (animate) map.getController().animateTo(p);
        else map.getController().setCenter(p);
    }

    /** Zooms so the whole route fits, with some margin. */
    public void fit(List<double[]> route) {
        if (route.size() < 2) {
            if (route.size() == 1) centre(route.get(0)[0], route.get(0)[1], false);
            return;
        }
        BoundingBox box = BoundingBox.fromGeoPointsSafe(points(route));
        int pad = (int) (48 * map.getContext().getResources().getDisplayMetrics().density);
        map.post(() -> map.zoomToBoundingBox(box.increaseByScale(1.25f), false, pad));
    }

    @Nullable
    public MapView view() {
        return map;
    }
}
