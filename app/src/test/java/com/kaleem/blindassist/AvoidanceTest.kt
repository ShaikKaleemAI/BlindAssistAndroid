package com.kaleem.blindassist

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AvoidanceTest {
    @Before fun reset() { Cfg.verbose = false; Cfg.lang = "en"; Track.nextId = 0 }

    // ---- steering ----
    @Test fun emptySceneIsClearButNeverCalledSafe() { assertEquals("clear", Avoidance.plan(emptyList()).state) }
    @Test fun objectFarToTheSideIsNotInPath() { assertEquals("clear", Avoidance.plan(listOf(mkTrack("person", 2.0, 3.0))).state) }

    @Test fun obstacleOnRightVeersLeftAndViceVersa() {
        val right = Avoidance.plan(listOf(mkTrack("person", 2.0, 0.35)))
        assertEquals("veer", right.state); assertEquals("left", right.side)
        val left = Avoidance.plan(listOf(mkTrack("person", 2.0, -0.35)))
        assertEquals("veer", left.state); assertEquals("right", left.side)
    }

    @Test fun wallOfObstaclesIsBlocked() {
        val g = Avoidance.plan(listOf(mkTrack("bus", 2.0, 0.0), mkTrack("person", 2.0, -1.9), mkTrack("person", 2.0, 1.9)))
        assertEquals("blocked", g.state)
    }

    // ---- alert level ----
    @Test fun levelsByDistanceForAPersonStraightAhead() {
        assertEquals(Level.CRITICAL, classify(mkTrack("person", 1.0)))
        assertEquals(Level.WARNING, classify(mkTrack("person", 2.0)))
        assertEquals(Level.INFO, classify(mkTrack("person", 4.0)))
        assertEquals(Level.NONE, classify(mkTrack("person", 6.0)))
    }

    @Test fun vehiclesGetDoubleDistanceMargins() { assertEquals(Level.CRITICAL, classify(mkTrack("car", 2.0))) }
    @Test fun offPathObjectIsIgnored() { assertEquals(Level.NONE, classify(mkTrack("person", 2.0, 3.0))) }

    /** Classifier is ready for hazard labels (once Stage 2 gives them a range). */
    @Test fun groundHazardInCorridorEscalates() {
        assertEquals(Level.CRITICAL, classify(mkTrack("stairs", 1.5)))
        assertEquals(Level.WARNING, classify(mkTrack("stairs", 2.5)))
        assertEquals(Level.NONE, classify(mkTrack("stairs", 4.0)))
    }
}
