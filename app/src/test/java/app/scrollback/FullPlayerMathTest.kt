package app.scrollback

import app.scrollback.fullplayer.AbRepeat
import app.scrollback.fullplayer.AspectMode
import app.scrollback.fullplayer.LoopbackStream
import app.scrollback.fullplayer.PlayerMath
import app.scrollback.fullplayer.PlayerMath.Gesture
import app.scrollback.remote.HttpException
import app.scrollback.remote.jellyfin.JellyfinErrors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FullPlayerMathTest {
    private fun assertDim(w: Double, h: Double, d: app.scrollback.fullplayer.Dim) {
        assertEquals(w, d.w, 0.5); assertEquals(h, d.h, 0.5)
    }

    // ---- Aspect ----

    @Test fun fitLetterboxesWideVideoOnPortraitScreen() =
        assertDim(1080.0, 607.5, PlayerMath.displaySize(AspectMode.FIT, 1920, 1080, 1, 1, 1080, 2340))

    @Test fun fitPillarboxesTallVideoOnLandscapeScreen() =
        assertDim(607.5, 1080.0, PlayerMath.displaySize(AspectMode.FIT, 1080, 1920, 1, 1, 2340, 1080))

    @Test fun cropFillsScreenAndOverflows() {
        val d = PlayerMath.displaySize(AspectMode.CROP, 1920, 1080, 1, 1, 2340, 1080)
        assertDim(2340.0, 1316.25, d) // wider screen than 16:9 → width fills, height overflows
    }

    @Test fun fillStretchesToScreen() = assertDim(2340.0, 1080.0, PlayerMath.displaySize(AspectMode.FILL, 640, 480, 1, 1, 2340, 1080))

    @Test fun forcedRatiosIgnoreVideoShape() {
        assertDim(1920.0, 1080.0, PlayerMath.displaySize(AspectMode.R16_9, 720, 576, 1, 1, 2340, 1080))
        assertDim(1440.0, 1080.0, PlayerMath.displaySize(AspectMode.R4_3, 1920, 1080, 1, 1, 2340, 1080))
    }

    @Test fun anamorphicSarIsApplied() {
        // DVD PAL 720×576 with SAR 64:45 is 16:9 on screen.
        val d = PlayerMath.displaySize(AspectMode.FIT, 720, 576, 64, 45, 2340, 1080)
        assertEquals(16.0 / 9.0, d.w / d.h, 0.01)
    }

    @Test fun originalKeepsPixelSize() = assertDim(640.0, 360.0, PlayerMath.displaySize(AspectMode.ORIGINAL, 640, 360, 0, 0, 2340, 1080))

    @Test fun unknownVideoSizeFillsScreen() = assertDim(2340.0, 1080.0, PlayerMath.displaySize(AspectMode.FIT, 0, 0, 0, 0, 2340, 1080))

    @Test fun surfaceIncludesDecoderPadding() {
        val disp = PlayerMath.displaySize(AspectMode.FIT, 1920, 1080, 1, 1, 1920, 1080)
        assertEquals(1920 to 1088, PlayerMath.surfaceSize(disp, 1920, 1088, 1920, 1080))
    }

    @Test fun aspectModesCycle() {
        var m = AspectMode.FIT
        repeat(AspectMode.entries.size) { m = m.next() }
        assertEquals(AspectMode.FIT, m)
    }

    // ---- Gestures ----

    @Test fun classifyGestures() {
        assertEquals(Gesture.NONE, PlayerMath.classify(3f, 4f, 100f, 1000f, 16f))
        assertEquals(Gesture.SEEK, PlayerMath.classify(80f, 10f, 100f, 1000f, 16f))
        assertEquals(Gesture.BRIGHTNESS, PlayerMath.classify(5f, -60f, 200f, 1000f, 16f))
        assertEquals(Gesture.VOLUME, PlayerMath.classify(5f, 60f, 800f, 1000f, 16f))
    }

    @Test fun verticalDragChangesLevelAndClamps() {
        assertEquals(0.5f + 0.25f, PlayerMath.levelAfterDrag(0.5f, -200f, 1000f), 0.001f) // up 200 of 800 effective
        assertEquals(1f, PlayerMath.levelAfterDrag(0.9f, -900f, 1000f), 0.0f)
        assertEquals(0f, PlayerMath.levelAfterDrag(0.1f, 900f, 1000f), 0.0f)
        assertEquals(8, PlayerMath.volumeStep(0.5f, 15))
    }

    @Test fun horizontalSeekScalesWithDurationAndClamps() {
        // Long movie: full width = 3 minutes.
        assertEquals(60_000L + 90_000L, PlayerMath.seekTarget(60_000, 500f, 1000f, 7_200_000))
        // Short clip: full width = the whole clip.
        assertEquals(10_000L + 15_000L, PlayerMath.seekTarget(10_000, 500f, 1000f, 30_000))
        assertEquals(0L, PlayerMath.seekTarget(10_000, -1000f, 1000f, 30_000))
        assertEquals(30_000L, PlayerMath.seekTarget(29_000, 900f, 1000f, 30_000))
    }

    @Test fun doubleTapSidesSeekTenSecondsMiddleToggles() {
        assertEquals(40_000L, PlayerMath.doubleTapSeek(100f, 900f, 50_000, 100_000))
        assertEquals(60_000L, PlayerMath.doubleTapSeek(800f, 900f, 50_000, 100_000))
        assertNull(PlayerMath.doubleTapSeek(450f, 900f, 50_000, 100_000))
        assertEquals(0L, PlayerMath.doubleTapSeek(10f, 900f, 4_000, 100_000))
        assertEquals(100_000L, PlayerMath.doubleTapSeek(890f, 900f, 95_000, 100_000))
    }

    @Test fun zoomAndSpeedClamp() {
        assertEquals(1f, PlayerMath.clampZoom(0.5f)); assertEquals(4f, PlayerMath.clampZoom(9f))
        assertEquals(0.25f, PlayerMath.clampSpeed(0.1f)); assertEquals(4f, PlayerMath.clampSpeed(8f))
        assertEquals(0.25f, PlayerMath.SPEEDS.first()); assertEquals(4f, PlayerMath.SPEEDS.last())
    }

    @Test fun formatsTimes() {
        assertEquals("0:05", PlayerMath.formatTime(5_000))
        assertEquals("1:02:03", PlayerMath.formatTime(3_723_000))
        assertEquals("+0:15", PlayerMath.formatTime(15_000, signed = true))
        assertEquals("−1:00", PlayerMath.formatTime(-60_000, signed = true))
    }

    // ---- Resume ----

    @Test fun resumeOnlyFromMeaningfulPoint() {
        assertFalse(PlayerMath.shouldResume(3_000, 600_000))            // too early
        assertTrue(PlayerMath.shouldResume(120_000, 600_000))
        assertFalse(PlayerMath.shouldResume(595_000, 600_000))          // last 10 s
        assertFalse(PlayerMath.shouldResume(7_000_000, 7_200_000))      // last 3 %
        assertTrue(PlayerMath.shouldResume(60_000, 0))                  // unknown length
    }

    @Test fun positionToSaveClearsFinishedFiles() {
        assertEquals(120_000L, PlayerMath.positionToSave(120_000, 600_000))
        assertEquals(0L, PlayerMath.positionToSave(598_000, 600_000))
        assertEquals(0L, PlayerMath.positionToSave(-1, 600_000))
        assertEquals(2_000L, PlayerMath.positionToSave(2_000, 600_000)) // harmless: never resumed
    }

    // ---- A-B repeat ----

    @Test fun abRepeatCycle() {
        val ab = AbRepeat()
        ab.tap(10_000); assertFalse(ab.active)
        ab.tap(10_200); assertFalse(ab.active)          // too close, ignored
        ab.tap(20_000); assertTrue(ab.active)
        assertNull(ab.check(15_000)); assertEquals(10_000L, ab.check(20_100))
        ab.tap(0); assertFalse(ab.active); assertNull(ab.a)
        ab.tap(30_000); ab.tap(25_000)                   // B before A swaps
        assertEquals(25_000L, ab.a); assertEquals(30_000L, ab.b)
    }

    // ---- Loopback ranges ----

    @Test fun parsesRangeHeaders() {
        assertEquals(100L to null, LoopbackStream.Range.parse("bytes=100-"))
        assertEquals(0L to 499L, LoopbackStream.Range.parse("bytes=0-499"))
        assertNull(LoopbackStream.Range.parse(null))
        assertNull(LoopbackStream.Range.parse("bytes=0-1,5-9"))
    }

    // ---- Friendly Jellyfin errors ----

    @Test fun jellyfinErrorsHideRawExceptionText() {
        val raw = java.io.IOException("unexpected end of stream on http://127.0.0.1:8096/...")
        val msg = JellyfinErrors.friendly(raw)
        assertFalse(msg.contains("127.0.0.1")); assertFalse(msg.contains("unexpected"))
        assertTrue(JellyfinErrors.friendly(java.net.ConnectException("ECONNREFUSED 10.0.0.2")).startsWith("Can't reach"))
        assertTrue(JellyfinErrors.friendly(HttpException(401, "Jellyfin sign-in expired")).contains("Sign in again"))
        assertFalse(JellyfinErrors.friendly(HttpException(500, "boom")).contains("boom"))
        assertFalse(JellyfinErrors.friendly(IllegalStateException("NPE at Foo.kt:12")).contains("Foo"))
    }
}
