package fr.zeffut.multiview.merge;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SourcePovTrackerTest {

    @Test
    void positionAtReturnsNaNBeforeAnyUpdate() {
        SourcePovTracker t = new SourcePovTracker(2);
        assertTrue(Double.isNaN(t.positionAt(0, 100).x()),
                "Avant tout update, la position est NaN");
    }

    @Test
    void positionAtReturnsLatestKnownPositionBeforeOrEqualTick() {
        SourcePovTracker t = new SourcePovTracker(2);
        t.update(0, 10, 1.0, 2.0, 3.0);
        t.update(0, 20, 5.0, 6.0, 7.0);

        SourcePovTracker.Vec3 p15 = t.positionAt(0, 15);
        assertEquals(1.0, p15.x(), 0.0001);
        assertEquals(2.0, p15.y(), 0.0001);
        assertEquals(3.0, p15.z(), 0.0001);

        SourcePovTracker.Vec3 p25 = t.positionAt(0, 25);
        assertEquals(5.0, p25.x(), 0.0001);
    }

    @Test
    void sourcesAreIndependent() {
        SourcePovTracker t = new SourcePovTracker(2);
        t.update(0, 10, 1.0, 2.0, 3.0);
        t.update(1, 10, 100.0, 200.0, 300.0);

        assertEquals(1.0, t.positionAt(0, 10).x(), 0.0001);
        assertEquals(100.0, t.positionAt(1, 10).x(), 0.0001);
    }

    @Test
    void distanceReturnsInfiniteIfUnknown() {
        SourcePovTracker t = new SourcePovTracker(1);
        double d = t.distanceTo(0, 100, 1.0, 2.0, 3.0);
        assertTrue(Double.isInfinite(d));
    }

    @Test
    void distanceComputesEuclidean() {
        SourcePovTracker t = new SourcePovTracker(1);
        t.update(0, 10, 0.0, 0.0, 0.0);
        double d = t.distanceTo(0, 10, 3.0, 4.0, 0.0);
        assertEquals(5.0, d, 0.0001);
    }
}
