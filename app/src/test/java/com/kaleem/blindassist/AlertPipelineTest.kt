package com.kaleem.blindassist

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AlertPipelineTest {
    @Before fun reset() { Cfg.verbose = false; Cfg.lang = "en"; Track.nextId = 0 }

    private fun speeches(evs: List<Ev>) = evs.filterIsInstance<Ev.Speech>()

    @Test fun criticalObstacleSpeaksBeepsVibratesThenRepeatsOnSchedule() {
        val tr = mkTrack("person", 1.0)
        val eng = AlertEngine("en")
        val first = eng.update(listOf(tr), listOf(tr), 0.0)
        assertEquals(1, speeches(first).size)
        assertEquals(P_CRITICAL, speeches(first)[0].pri)
        assertTrue(speeches(first)[0].text.startsWith("Stop."))
        assertTrue(first.any { it is Ev.Beep }); assertTrue(first.any { it === Ev.Vibrate })
        assertEquals("no repeat after 0.5 s", 0, speeches(eng.update(listOf(tr), listOf(tr), 0.5)).size)
        assertEquals("repeat after CRITICAL_REPEAT", 1, speeches(eng.update(listOf(tr), listOf(tr), 2.6)).size)
    }

    @Test fun infoLevelIsSilentUnlessVerbose() {
        val tr = mkTrack("person", 4.0)
        assertTrue(AlertEngine("en").update(listOf(tr), listOf(tr), 0.0).isEmpty())
    }

    @Test fun hindiAndTeluguAlertsStartWithLocalisedStop() {
        val hi = speeches(AlertEngine("hi").update(listOf(mkTrack("person", 1.0)), listOf(mkTrack("person", 1.0)), 0.0))
        val te = speeches(AlertEngine("te").update(listOf(mkTrack("person", 1.0)), listOf(mkTrack("person", 1.0)), 0.0))
        assertTrue(hi[0].text.startsWith("रुकिए")); assertTrue(te[0].text.startsWith("ఆగండి"))
    }

    @Test fun emptySceneNeverProducesAnyEvent() {
        val p = Pipeline(Cam(60.0, 1.1, 0.0), "en")
        for (i in 0 until 50) assertTrue(p.process(emptyList(), i * 0.1, 640.0, 480.0, 0.0).isEmpty())
    }

    @Test fun staticFarPersonStaysSilent() {
        val cam = Cam(60.0, 1.1, 0.0); val p = Pipeline(cam, "en")
        for (i in 0 until 50)
            assertTrue(p.process(listOf(Det("person", boxFor("person", 6.0, cam, 640.0, 480.0), 0.9)), i * 0.1, 640.0, 480.0, 0.0).isEmpty())
    }

    /**
     * End-to-end on SYNTHETIC geometry (not real video): a car closes from 8 m at 3 m/s.
     * Expect the first spoken alert while it is still >= 2.6 m away. Most likely test to need tuning on first run (unrun).
     */
    @Test fun approachingCarIsAnnouncedEarly() {
        val cam = Cam(60.0, 1.1, 0.0); val p = Pipeline(cam, "en")
        var firstZ: Double? = null; var firstText = ""; var beeps = 0
        var i = 0
        while (8.0 - 3.0 * (i * 0.1) >= 2.6) {
            val t = i * 0.1; val z = 8.0 - 3.0 * t
            val evs = p.process(listOf(Det("car", boxFor("car", z, cam, 640.0, 480.0), 0.9)), t, 640.0, 480.0, 0.0)
            beeps += evs.count { it is Ev.Beep }
            val sp = speeches(evs)
            if (firstZ == null && sp.isNotEmpty()) { firstZ = z; firstText = sp[0].text }
            i++
        }
        assertNotNull("no alert at all", firstZ); assertTrue("first alert at ${firstZ}m", firstZ!! >= 2.6)
        assertTrue(firstText.contains("car")); assertTrue(beeps > 0)
    }
}
