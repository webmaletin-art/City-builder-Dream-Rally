package dev.dreamrally.citybuilder

import dev.dreamrally.citybuilder.ui.Camera
import org.junit.Assert.*
import org.junit.Test

class CameraTest {
    @Test fun projectThenUnprojectRoundTripsForAllRotations() {
        for (k in 0..3) {
            val cam = Camera(cx = 3.5f, cy = -7.25f, zoom = 56f, rotK = k).also { it.w = 1080f; it.h = 1920f }
            for ((x, y) in listOf(0f to 0f, 10.5f to 3f, -20f to 14.75f, 3.5f to -7.25f)) {
                val s = cam.project(x, y)
                val (bx, by) = cam.unproject(s.x, s.y)
                assertEquals("x k=$k", x, bx, 1e-3f); assertEquals("y k=$k", y, by, 1e-3f)
            }
            val c = cam.project(3.5f, -7.25f); assertEquals(540f, c.x, 1e-3f); assertEquals(960f, c.y, 1e-3f)
        }
    }

    @Test fun visibleRectCoversScreenCornersAndPanMovesCamera() {
        val cam = Camera(zoom = 40f, rotK = 1).also { it.w = 1000f; it.h = 800f }
        val r = cam.visibleCells(0)
        for ((sx, sy) in listOf(0f to 0f, 1000f to 0f, 0f to 800f, 1000f to 800f)) {
            val (x, y) = cam.unproject(sx, sy)
            assertTrue(x >= r[0] - 1 && x <= r[2] + 1 && y >= r[1] - 1 && y <= r[3] + 1)
        }
        val before = cam.project(5f, 5f)
        cam.panByScreen(100f, -40f)
        val after = cam.project(5f, 5f)
        assertEquals(before.x + 100f, after.x, 1e-3f); assertEquals(before.y - 40f, after.y, 1e-3f)
    }

    @Test fun depthIncreasesTowardViewer() {
        val cam = Camera(rotK = 0).also { it.w = 100f; it.h = 100f }
        assertTrue(cam.depth(5f, 5f) > cam.depth(1f, 1f))
        assertTrue(cam.project(5f, 5f).y > cam.project(1f, 1f).y)
    }
}
