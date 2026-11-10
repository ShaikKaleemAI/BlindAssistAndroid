package com.kaleem.blindassist

import org.junit.Assert.*
import org.junit.Test

class RangeFusionTest {
    private val cam = Cam(60.0, 1.1, 0.0)
    private val fw = 640.0; private val fh = 480.0

    @Test fun consistentCuesGiveExactRange() {
        val r = estimateRange("person", boxFor("person", 3.0, cam, fw, fh), fw, fh, cam, 0.0)!!
        assertEquals(3.0, r.z, 0.01); assertEquals(0.0, r.bearing, 0.01)
        assertTrue(r.flags.isEmpty())
    }

    /**
     * MODEL CONSISTENCY ONLY: synthetic boxes from the same pinhole model with 2 px detector jitter.
     * This is NOT a distance-accuracy result; the real 10% target needs the tape-measure benchmark (Stage 9).
     */
    @Test fun twoPixelJitterStaysWithinFivePercent() {
        for (label in listOf("car", "person")) for (z in listOf(3.0, 4.0, 5.0, 6.0)) for (j in listOf(2.0, -2.0)) {
            val r = estimateRange(label, boxFor(label, z, cam, fw, fh, j), fw, fh, cam, 0.0)!!
            assertTrue("$label z=$z jitter=$j got ${r.z}", Math.abs(r.z - z) / z < 0.05)
        }
    }

    @Test fun boxClippedTopAndBottomFallsBackToWidthAndFlagsIt() {
        // person 1 m away: head and feet are outside the frame, only the width cue is usable
        val r = estimateRange("person", doubleArrayOf(181.5, 0.0, 458.5, 479.0), fw, fh, cam, 0.0)!!
        assertEquals(1.0, r.z, 0.02)
        assertTrue("clipped_top" in r.flags); assertTrue("clipped_bottom" in r.flags)
    }

    @Test fun boxFillingTheFrameIsBoundOnlyAndClose() {
        val r = estimateRange("person", doubleArrayOf(0.0, 0.0, 640.0, 479.0), fw, fh, cam, 0.0)!!
        assertTrue("bound_only" in r.flags); assertTrue(r.z < 1.0)   // fail-safe: reports it as near
    }

    /** Characterisation of a KNOWN GAP: labels in Cfg.GROUND_HAZARDS have no size/ground model, so they get no range
     *  and Pipeline drops them. Stage 2 must fix this; this test is expected to be changed then. */
    @Test fun knownGap_groundHazardLabelsGetNoRangeYet() {
        assertNull(estimateRange("stairs", doubleArrayOf(100.0, 100.0, 300.0, 200.0), fw, fh, cam, 0.0))
    }
}
