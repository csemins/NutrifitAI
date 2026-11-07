package com.transformsai.nutrifitai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.transformsai.nutrifitai.fitness.RouteTracker;

import org.junit.Test;

import java.util.List;

/** GPS walk routes: filtering, distance, moving time, elevation, splits and the stored polyline. */
public class RouteTrackerTest {
    /** Metres per degree of latitude (close enough for test spacing). */
    private static final double M_PER_DEG = 111_195;

    private static double north(double metres) {
        return metres / M_PER_DEG;
    }

    @Test
    public void walkingNorthAddsUpDistanceAndPace() {
        RouteTracker r = new RouteTracker();
        long t = 0;
        for (int i = 0; i <= 100; i++) { // 10 m every 7 s ≈ 1.43 m/s, 1 km in total
            assertTrue(r.add(23.8 + north(i * 10), 90.4, Double.NaN, 5f, t));
            t += 7000;
        }
        assertEquals(1000, r.distanceM(), 2);
        assertEquals(700_000, r.movingMs());
        assertEquals(1, r.splitsMs().size());
        assertEquals("11:40", RouteTracker.pace(r.averageSpeedMps()));
    }

    @Test
    public void inaccurateFixesAndJumpsAreDropped() {
        RouteTracker r = new RouteTracker();
        assertTrue(r.add(23.8, 90.4, Double.NaN, 5f, 0));
        assertFalse("too inaccurate", r.add(23.8 + north(20), 90.4, Double.NaN, 80f, 5000));
        assertFalse("500 m in 5 s is a GPS jump", r.add(23.8 + north(500), 90.4, Double.NaN, 5f, 5000));
        assertFalse("1 m is noise", r.add(23.8 + north(1), 90.4, Double.NaN, 5f, 6000));
        assertTrue(r.add(23.8 + north(10), 90.4, Double.NaN, 5f, 10000));
        assertEquals(10, r.distanceM(), 0.5);
    }

    @Test
    public void pausesDoNotCountAsMovingTime() {
        RouteTracker r = new RouteTracker();
        r.add(23.8, 90.4, Double.NaN, 5f, 0);
        r.add(23.8 + north(10), 90.4, Double.NaN, 5f, 7000);
        r.add(23.8 + north(20), 90.4, Double.NaN, 5f, 7000 + 120_000); // two-minute stop, then on
        assertEquals(7000, r.movingMs());
        assertEquals(20, r.distanceM(), 0.5);
    }

    @Test
    public void elevationGainIgnoresNoiseAndDescents() {
        RouteTracker r = new RouteTracker();
        double[] alts = {10, 11, 9, 12, 16, 20, 15, 10, 14, 18};
        long t = 0;
        for (int i = 0; i < alts.length; i++) {
            r.add(23.8 + north(i * 10), 90.4, alts[i], 5f, t);
            t += 7000;
        }
        // climbs counted: 10→16 (+6), 16→20 (+4), then after descending to 10: 10→14 (+4), 14→18 (+4)
        assertEquals(18, r.elevationGainM(), 0.01);
    }

    @Test
    public void polylineRoundTrips() {
        RouteTracker r = new RouteTracker();
        r.add(23.81037, 90.41252, Double.NaN, 5f, 0);
        r.add(23.81100, 90.41300, Double.NaN, 5f, 60_000);
        r.add(23.80950, 90.41010, Double.NaN, 5f, 300_000);
        List<double[]> back = RouteTracker.decode(r.encoded());
        assertEquals(3, back.size());
        assertEquals(23.80950, back.get(2)[0], 1e-5);
        assertEquals(90.41010, back.get(2)[1], 1e-5);
        assertEquals("_p~iF~ps|U_ulLnnqC_mqNvxq`@",
                RouteTracker.encode(java.util.Arrays.asList(new RouteTracker.Point(38.5, -120.2, 0, 0),
                        new RouteTracker.Point(40.7, -120.95, 0, 0), new RouteTracker.Point(43.252, -126.453, 0, 0))));
        assertTrue(RouteTracker.decode("").isEmpty());
        assertTrue(RouteTracker.decode(null).isEmpty());
    }

    @Test
    public void paceFormatting() {
        assertEquals("–", RouteTracker.pace(0));
        assertEquals("5:00", RouteTracker.pace(1000 / 300.0));
    }
}
