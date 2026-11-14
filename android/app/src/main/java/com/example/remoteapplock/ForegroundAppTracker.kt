package com.example.remoteapplock

/** Usage queries overlap to allow delayed delivery. Do not replay old foreground transitions. */
class ForegroundAppTracker {
    enum class Transition { RESUMED, PAUSED, STOPPED }

    var packageName: String? = null
        private set
    var activityName: String? = null
        private set
    private var latestTime = Long.MIN_VALUE
    private val eventsAtLatestTime = mutableSetOf<Triple<String, String?, Transition>>()

    fun record(pkg: String, activity: String?, transition: Transition, time: Long): Boolean {
        if (time < latestTime) return false
        if (time > latestTime) {
            latestTime = time
            eventsAtLatestTime.clear()
        }
        if (!eventsAtLatestTime.add(Triple(pkg, activity, transition))) return false
        when (transition) {
            Transition.RESUMED -> {
                packageName = pkg
                activityName = activity
            }
            Transition.PAUSED, Transition.STOPPED -> if (pkg == packageName && activity == activityName) {
                packageName = null
                activityName = null
            }
        }
        return true
    }

    fun reset() {
        packageName = null
        activityName = null
        latestTime = Long.MIN_VALUE
        eventsAtLatestTime.clear()
    }
}
