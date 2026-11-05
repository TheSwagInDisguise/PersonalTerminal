package dev.personalterminal.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the navigation change that keeps screenshot goldens unchanged. */
class NavigationTabsTest {

    @Test fun `real bottom bar gives planner a dedicated slot`() {
        assertEquals(listOf(Tab.TODAY, Tab.HABITS, Tab.TIMER, Tab.WATCH, Tab.PLANNER), appTabs)
        assertTrue(appTabs.contains(Tab.PLANNER))
        assertFalse(appTabs.contains(Tab.PROFILE))
    }

    @Test fun `screenshot goldens keep the original five tabs`() {
        assertEquals(listOf(Tab.TODAY, Tab.HABITS, Tab.TIMER, Tab.WATCH, Tab.PROFILE), goldenTabs)
        assertTrue(goldenTabs.contains(Tab.PROFILE))
        assertFalse(goldenTabs.contains(Tab.PLANNER))
    }
}
