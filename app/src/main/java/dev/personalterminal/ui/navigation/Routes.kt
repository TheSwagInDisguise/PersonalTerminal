package dev.personalterminal.ui.navigation

object Routes {
    const val TODAY = "today"
    const val HABITS = "habits"
    const val HABIT_EDIT = "habit/edit?id={id}&routineId={routineId}&name={name}"
    const val HABIT_DETAIL = "habit/{id}"
    const val ROUTINES = "routines"
    const val TIMER = "timer?habitId={habitId}"
    const val TIMELINE = "timeline"
    const val WATCHES = "watches"
    const val WATCH_EDIT = "watch/edit?id={id}&wish={wish}"
    const val WATCH_DETAIL = "watch/{id}"
    const val WEAR_LOG = "wear/log?day={day}"
    const val PROFILE = "profile"
    const val SETTINGS = "settings"
    const val REVIEW = "review"
    const val ACHIEVEMENTS = "man/achievements"
    const val INSIGHTS = "insights"
    const val TEMPLATES = "habit/templates"
    const val SESSIONS = "timer/sessions"
    const val STRAPS = "straps"
    const val WATCH_STATS = "watch/stats"
    const val WATCH_BOX = "watch/box"
    const val WISHLIST = "watch/wishlist"
    const val WISHLIST_TAB = "watch/wishlist?tab={tab}"
    const val YEAR_REVIEW = "review/year"
    const val SKIP_RULES = "habit/insurance"
    const val IMPORT = "import"
    const val JOURNAL = "journal"
    const val GARDEN = "garden"

    fun habitEdit(id: Long = 0, routineId: Long? = null, name: String? = null) =
        "habit/edit?id=$id&routineId=${routineId ?: -1}" + (name?.let { "&name=${android.net.Uri.encode(it)}" } ?: "")
    fun habitDetail(id: Long) = "habit/$id"
    fun timer(habitId: Long = 0) = "timer?habitId=$habitId"
    fun watchEdit(id: Long = 0, wish: Boolean = false) = "watch/edit?id=$id&wish=${if (wish) 1 else 0}"
    fun watchDetail(id: Long) = "watch/$id"
    fun wearLog(day: Long) = "wear/log?day=$day"
}

/** Bottom "tab bar" rendered like a tmux status line. */
enum class Tab(val route: String, val label: String, val index: Int) {
    TODAY(Routes.TODAY, "today", 0),
    HABITS(Routes.HABITS, "habits", 1),
    TIMER(Routes.timer(), "timer", 2),
    WATCH(Routes.WATCHES, "watch", 3),
    PROFILE(Routes.PROFILE, "profile", 4),
}
