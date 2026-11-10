package com.kaleem.blindassist

import org.junit.Assert.*
import org.junit.Test

class GeometryTest {
    private val cam = Cam(60.0, 1.0, 0.0)

    @Test fun groundPixelMapsToKnownDistance() {
        val fx = cam.fx(640.0)
        // pixel whose ray hits the ground 3 m ahead of a 1 m high camera: tan(down) = 1/3
        val z = cam.groundZ(320.0, 240.0 + fx / 3.0, 640.0, 480.0, 0.0)
        assertNotNull(z); assertEquals(3.0, z!!, 1e-6)
    }

    @Test fun horizonAndSkyHaveNoGroundDistance() {
        assertNull(cam.groundZ(320.0, 240.0, 640.0, 480.0, 0.0))
        assertNull(cam.groundZ(320.0, 100.0, 640.0, 480.0, 0.0))
    }

    @Test fun pitchedDownCameraSeesGroundAtCentre() {
        val z = cam.groundZ(320.0, 240.0, 640.0, 480.0, 10.0)   // centre ray, camera pitched down 10 deg
        assertEquals(1.0 / Math.tan(Math.toRadians(10.0)), z!!, 1e-3)
    }

    @Test fun bearingIsSignedAndMatchesFov() {
        assertEquals(0.0, cam.bearingDeg(320.0, 640.0), 1e-9)
        assertEquals(30.0, cam.bearingDeg(640.0, 640.0), 1e-6)       // right edge = half of 60 deg hfov
        assertEquals(-30.0, cam.bearingDeg(0.0, 640.0), 1e-6)
    }

    @Test fun clockPositions() {
        assertEquals(12, clockHour(0.0)); assertEquals(1, clockHour(30.0)); assertEquals(11, clockHour(-30.0))
        assertEquals(3, clockHour(90.0)); assertEquals(9, clockHour(-90.0)); assertEquals(6, clockHour(180.0))
    }
}
