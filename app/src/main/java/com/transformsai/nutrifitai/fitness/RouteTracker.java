package com.transformsai.nutrifitai.fitness;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * GPS route of a walk, built from raw location fixes (pure Java, unit-tested). It drops fixes that are too
 * inaccurate, too close to the last point, or that would mean an impossible jump, then keeps distance, moving time
 * (pauses don't count), current and best speed, elevation gain (with a small dead band against GPS noise) and
 * per-kilometre splits. The route is stored compactly as an encoded polyline.
 */
public final class RouteTracker {
    /** Fixes worse than this are ignored (metres, 68% confidence radius). */
    public static final float MAX_ACCURACY_M = 25f;
    /** Ignore movement smaller than this between kept points (metres). */
    public static final double MIN_STEP_M = 3;
    /** Faster than this between two fixes is a GPS jump, not walking or running (m/s, about 43 km/h). */
    public static final double MAX_SPEED_MPS = 12;
    /** Slower than this counts as standing still (m/s). */
    public static final double MOVING_MPS = 0.5;
    /** Elevation changes inside this band are treated as noise (metres). */
    public static final double ELEVATION_BAND_M = 3;
    /** Longest gap that still counts as moving time (ms); a longer gap means the walk was paused. */
    public static final long MAX_GAP_MS = 30_000;
    public static final int MAX_POINTS = 8000;

    /** One kept fix. */
    public static final class Point {
        public final double lat, lon, alt;
        public final long time;

        public Point(double lat, double lon, double alt, long time) {
            this.lat = lat;
            this.lon = lon;
            this.alt = alt;
            this.time = time;
        }
    }

    private final List<Point> points = new ArrayList<>();
    private final List<Long> splits = new ArrayList<>();
    private double distance, maxSpeed, currentSpeed, elevGain;
    private long moving, splitStartMoving;
    private double altRef = Double.NaN;

    /**
     * Offers a fix. {@code alt} may be NaN; {@code accuracy} in metres (NaN when unknown is treated as good enough).
     * Returns true when the point was kept.
     */
    public synchronized boolean add(double lat, double lon, double alt, float accuracy, long time) {
        if (!Float.isNaN(accuracy) && accuracy > MAX_ACCURACY_M) return false;
        if (Double.isNaN(lat) || Double.isNaN(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180) return false;
        Point p = new Point(lat, lon, alt, time);
        if (points.isEmpty()) {
            points.add(p);
            if (!Double.isNaN(alt)) altRef = alt;
            return true;
        }
        Point last = points.get(points.size() - 1);
        long dt = time - last.time;
        if (dt <= 0) return false;
        double d = distance(last.lat, last.lon, lat, lon);
        double v = d / (dt / 1000.0);
        if (v > MAX_SPEED_MPS) return false; // GPS jump
        if (d < MIN_STEP_M) {
            if (dt > 5000) currentSpeed = 0; // standing still
            return false;
        }
        points.add(p);
        if (points.size() > MAX_POINTS) thin();
        distance += d;
        currentSpeed = v;
        if (v >= MOVING_MPS && dt <= MAX_GAP_MS) {
            moving += dt;
            if (dt >= 2000) maxSpeed = Math.max(maxSpeed, v); // very short gaps exaggerate speed
        }
        if (!Double.isNaN(alt)) {
            if (Double.isNaN(altRef)) altRef = alt;
            else if (alt - altRef >= ELEVATION_BAND_M) {
                elevGain += alt - altRef;
                altRef = alt;
            } else if (altRef - alt >= ELEVATION_BAND_M) {
                altRef = alt; // going down resets the reference, it doesn't count
            }
        }
        while (distance >= (splits.size() + 1) * 1000.0) { // a kilometre completed
            splits.add(moving - splitStartMoving);
            splitStartMoving = moving;
        }
        return true;
    }

    /** Keeps every other point (except the last) when a very long walk reaches the point limit. */
    private void thin() {
        List<Point> kept = new ArrayList<>(points.size() / 2 + 1);
        for (int i = 0; i < points.size(); i++) if (i % 2 == 0 || i == points.size() - 1) kept.add(points.get(i));
        points.clear();
        points.addAll(kept);
    }

    public synchronized double distanceM() {
        return distance;
    }

    public synchronized long movingMs() {
        return moving;
    }

    public synchronized double currentSpeedMps() {
        return currentSpeed;
    }

    public synchronized double maxSpeedMps() {
        return maxSpeed;
    }

    public synchronized double elevationGainM() {
        return elevGain;
    }

    /** Average moving speed (m/s); 0 before moving. */
    public synchronized double averageSpeedMps() {
        return moving <= 0 ? 0 : distance / (moving / 1000.0);
    }

    /** Completed kilometre splits, moving time per km in ms. */
    public synchronized List<Long> splitsMs() {
        return new ArrayList<>(splits);
    }

    public synchronized List<Point> points() {
        return new ArrayList<>(points);
    }

    public synchronized int size() {
        return points.size();
    }

    @Nullable
    public synchronized Point last() {
        return points.isEmpty() ? null : points.get(points.size() - 1);
    }

    public synchronized String encoded() {
        return encode(points);
    }

    // ---------------------------------------------------------------- helpers
    /** Great-circle distance in metres (haversine). */
    public static double distance(double lat1, double lon1, double lat2, double lon2) {
        double r = 6_371_008.8;
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
        double dp = p2 - p1, dl = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * r * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /** "9:42" minutes per km; "–" while not moving. */
    public static String pace(double speedMps) {
        if (speedMps < MOVING_MPS) return "–";
        long s = Math.round(1000 / speedMps);
        if (s >= 60 * 60) return "–";
        return String.format(Locale.getDefault(), "%d:%02d", s / 60, s % 60);
    }

    /** Google encoded polyline (1e5 precision) of the points' lat/lon. */
    public static String encode(List<Point> pts) {
        StringBuilder sb = new StringBuilder(pts.size() * 6);
        long pLat = 0, pLon = 0;
        for (Point p : pts) {
            long lat = Math.round(p.lat * 1e5), lon = Math.round(p.lon * 1e5);
            enc(lat - pLat, sb);
            enc(lon - pLon, sb);
            pLat = lat;
            pLon = lon;
        }
        return sb.toString();
    }

    private static void enc(long v, StringBuilder sb) {
        v = v < 0 ? ~(v << 1) : v << 1;
        while (v >= 0x20) {
            sb.append((char) ((0x20 | (v & 0x1f)) + 63));
            v >>= 5;
        }
        sb.append((char) (v + 63));
    }

    /** Decodes {@link #encode} output to [lat, lon] pairs; empty on bad input. */
    public static List<double[]> decode(@Nullable String s) {
        if (s == null || s.isEmpty()) return Collections.emptyList();
        List<double[]> out = new ArrayList<>();
        int i = 0;
        long lat = 0, lon = 0;
        try {
            while (i < s.length()) {
                long[] r = dec(s, i);
                lat += r[0];
                i = (int) r[1];
                r = dec(s, i);
                lon += r[0];
                i = (int) r[1];
                out.add(new double[]{lat / 1e5, lon / 1e5});
            }
        } catch (IndexOutOfBoundsException e) {
            return Collections.emptyList();
        }
        return out;
    }

    private static long[] dec(String s, int i) {
        long result = 0;
        int shift = 0, b;
        do {
            b = s.charAt(i++) - 63;
            result |= (long) (b & 0x1f) << shift;
            shift += 5;
        } while (b >= 0x20);
        return new long[]{(result & 1) != 0 ? ~(result >> 1) : result >> 1, i};
    }
}
