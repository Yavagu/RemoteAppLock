package com.example.remoteapplock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import com.example.remoteapplock.ForegroundAppTracker.Transition.PAUSED
import com.example.remoteapplock.ForegroundAppTracker.Transition.RESUMED
import com.example.remoteapplock.ForegroundAppTracker.Transition.STOPPED

class ForegroundAppTrackerTest {
    @Test fun `fast Recents round trip exposes departure even within one query batch`() {
        val tracker = ForegroundAppTracker()
        tracker.record("video", "Player", RESUMED, 100)
        assertTrue(tracker.record("video", "Player", PAUSED, 110))
        assertNull(tracker.packageName)
        assertTrue(tracker.record("launcher", "RecentsActivity", RESUMED, 120))
        assertEquals("RecentsActivity", tracker.activityName)
        assertTrue(tracker.record("video", "Player", RESUMED, 130))
        assertEquals("video", tracker.packageName)
        assertEquals("Player", tracker.activityName)
        // A repeated overlapping query must not cause an extra dismissal/animation.
        assertFalse(tracker.record("video", "Player", RESUMED, 130))
    }

    @Test fun `stop without a pause invalidates the old foreground`() {
        val tracker = ForegroundAppTracker()
        tracker.record("video", "Player", RESUMED, 100)
        tracker.record("video", "Player", STOPPED, 110)
        assertNull(tracker.packageName)
        tracker.record("video", "Player", RESUMED, 120)
        assertEquals("video", tracker.packageName)
    }

    @Test fun `payment activity within the same package replaces the foreground identity`() {
        val tracker = ForegroundAppTracker()
        tracker.record("shopping", "Home", RESUMED, 100)
        tracker.record("shopping", "PaymentActivity", RESUMED, 110)
        tracker.record("shopping", "Home", PAUSED, 111)
        assertEquals("PaymentActivity", tracker.activityName)
        tracker.record("shopping", "Home", RESUMED, 120)
        assertEquals("Home", tracker.activityName)
    }

    @Test fun `switching to another app releases the previous app`() {
        val tracker = ForegroundAppTracker()
        tracker.record("video", "Player", RESUMED, 10)
        tracker.record("launcher", "Home", RESUMED, 20)
        tracker.record("video", "Player", PAUSED, 21)
        assertEquals("launcher", tracker.packageName)
    }

    @Test fun `overlapping queries do not replay an old locked app`() {
        val tracker = ForegroundAppTracker()
        tracker.record("video", "Player", RESUMED, 10)
        tracker.record("launcher", "Home", RESUMED, 20)
        tracker.record("video", "Player", RESUMED, 10)
        assertEquals("launcher", tracker.packageName)
    }

    @Test fun `same timestamp transitions remain ordered when query repeats`() {
        val tracker = ForegroundAppTracker()
        tracker.record("video", "Player", RESUMED, 10)
        tracker.record("video", "Player", PAUSED, 10)
        tracker.record("video", "Player", RESUMED, 10)
        assertNull(tracker.packageName)
        tracker.record("launcher", "Home", RESUMED, 10)
        assertEquals("launcher", tracker.packageName)
    }

    @Test fun `pausing an old activity does not clear a newer activity in the same app`() {
        val tracker = ForegroundAppTracker()
        tracker.record("video", "Player", RESUMED, 10)
        tracker.record("video", "Details", RESUMED, 20)
        tracker.record("video", "Player", PAUSED, 21)
        assertEquals("video", tracker.packageName)
        tracker.record("video", "Details", PAUSED, 30)
        assertNull(tracker.packageName)
    }

    @Test fun `returning to a previously locked app is detected`() {
        val tracker = ForegroundAppTracker()
        tracker.record("video", "Player", RESUMED, 10)
        tracker.record("launcher", "Home", RESUMED, 20)
        tracker.record("video", "Player", RESUMED, 30)
        assertEquals("video", tracker.packageName)
    }

    @Test fun `reset permits recovery after the device clock moves backwards`() {
        val tracker = ForegroundAppTracker()
        tracker.record("video", "Player", RESUMED, 100)
        tracker.reset()
        assertNull(tracker.packageName)
        tracker.record("launcher", "Home", RESUMED, 20)
        assertEquals("launcher", tracker.packageName)
    }
}
