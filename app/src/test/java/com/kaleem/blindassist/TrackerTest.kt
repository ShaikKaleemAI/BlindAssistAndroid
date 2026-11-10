package com.kaleem.blindassist

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import kotlin.math.exp

class TrackerTest {
    private val fw = 480.0; private val fh = 640.0
    @Before fun reset() { Track.nextId = 0 }

    private fun det(box: DoubleArray, z: Double) = Det("person", box, 0.9, RangeEst(z, 0.0, 0.3, 0.0, emptySet()))

    @Test fun kalmanLearnsClosingSpeed() {
        val k = RangeKalman(5.0, 0.2)
        for (i in 1..30) { k.predict(0.1); k.update(5.0 - 0.1 * i, 0.2) }   // noise-free target closing at 1 m/s
        assertEquals(2.0, k.z, 0.1); assertEquals(-1.0, k.v, 0.1)
    }

    @Test fun loomingTtcMatchesGroundTruth() {
        val est = RangeEst(5.0, 0.0, 0.2, 0.0, emptySet())
        fun box(s: Double) = doubleArrayOf(240 - s / 2, 320 - s / 2, 240 + s / 2, 320 + s / 2)
        val tr = Track("car", box(40.0), est, 0.0, 0.9)
        tr.pushSize(0.0, box(40.0), fw, fh)
        for (i in 1..14) { val t = i * 0.1; tr.update(box(40.0 * exp(t / 3.0)), est, t, 0.9, fw, fh) }   // size grows with tau = 3 s
        assertNotNull(tr.ttc); assertEquals(3.0, tr.ttc!!, 0.05)
    }

    @Test fun staticObjectHasNoTtc() {
        val est = RangeEst(5.0, 0.0, 0.2, 0.0, emptySet())
        val b = doubleArrayOf(200.0, 250.0, 280.0, 400.0)
        val tr = Track("person", b, est, 0.0, 0.9)
        tr.pushSize(0.0, b, fw, fh)
        for (i in 1..14) tr.update(b, est, i * 0.1, 0.9, fw, fh)
        assertNull(tr.ttc)
    }

    @Test fun farTrackNeedsTwoHitsButCloseTrackIsConfirmedImmediately() {
        val tracker = Tracker()
        val b = doubleArrayOf(200.0, 100.0, 280.0, 400.0)
        assertTrue(tracker.update(listOf(det(b, 3.0)), 0.0, fw, fh).isEmpty())
        val c2 = tracker.update(listOf(det(b, 3.0)), 0.1, fw, fh)
        assertEquals(1, c2.size)
        val close = Tracker()
        assertEquals(1, close.update(listOf(det(b, 1.0)), 0.0, fw, fh).size)
    }

    @Test fun idsAreStableAndDistinctObjectsStaySeparate() {
        val tracker = Tracker()
        val a = doubleArrayOf(20.0, 100.0, 100.0, 400.0); val c = doubleArrayOf(350.0, 100.0, 430.0, 400.0)
        tracker.update(listOf(det(a, 3.0), det(c, 3.0)), 0.0, fw, fh)
        val r = tracker.update(listOf(det(a, 3.0), det(c, 3.0)), 0.1, fw, fh)
        assertEquals(2, r.size); assertEquals(2, r.map { it.id }.toSet().size)
        val r2 = tracker.update(listOf(det(a, 3.0), det(c, 3.0)), 0.2, fw, fh)
        assertEquals(r.map { it.id }.toSet(), r2.map { it.id }.toSet())
    }

    @Test fun staleTracksExpire() {
        val tracker = Tracker()
        tracker.update(listOf(det(doubleArrayOf(200.0, 100.0, 280.0, 400.0), 3.0)), 0.0, fw, fh)
        tracker.update(emptyList(), 1.0, fw, fh)     // > TRACK_MAX_AGE (0.8 s)
        assertTrue(tracker.tracks.isEmpty())
    }
}
