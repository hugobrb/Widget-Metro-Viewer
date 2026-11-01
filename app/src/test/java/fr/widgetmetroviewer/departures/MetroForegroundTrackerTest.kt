package fr.widgetmetroviewer.departures

import org.junit.Assert.*
import org.junit.Test

class MetroForegroundTrackerTest {
    @Test fun homeThenOtherAppThenHome() {
        val tracker = MetroForegroundTracker()
        tracker.resumed("home", "Main", 1)
        assertTrue(tracker.allowsRefresh(false, true, "home"))
        tracker.resumed("twitch", "Video", 2)
        assertFalse(tracker.allowsRefresh(false, true, "home"))
        tracker.resumed("home", "Main", 3)
        assertTrue(tracker.allowsRefresh(false, true, "home"))
    }
    @Test fun replayDoesNotOverrideLatestActivity() {
        val tracker = MetroForegroundTracker()
        tracker.resumed("twitch", "Video", 3)
        tracker.resumed("home", "Main", 1)
        tracker.paused("twitch", "Video", 2)
        assertFalse(tracker.allowsRefresh(false, true, "home"))
    }
    @Test fun pauseOfPreviousActivityDoesNotClearNewActivity() {
        val tracker = MetroForegroundTracker()
        tracker.resumed("home", "Old", 1)
        tracker.resumed("home", "New", 2)
        tracker.paused("home", "Old", 3)
        assertTrue(tracker.allowsRefresh(false, true, "home"))
        tracker.paused("home", "New", 4)
        assertFalse(tracker.allowsRefresh(false, true, "home"))
    }
    @Test fun permissionAndKnownHomeRequired() {
        val tracker = MetroForegroundTracker()
        tracker.resumed("home", "Main", 1)
        assertFalse(tracker.allowsRefresh(false, false, "home"))
        assertFalse(tracker.allowsRefresh(false, true, null))
        assertTrue(tracker.allowsRefresh(true, false, null))
    }
    @Test fun clearingDiscardsPreviousHome() {
        val tracker = MetroForegroundTracker()
        tracker.resumed("home", "Main", 1)
        tracker.clear()
        assertFalse(tracker.allowsRefresh(false, true, "home"))
    }
}
