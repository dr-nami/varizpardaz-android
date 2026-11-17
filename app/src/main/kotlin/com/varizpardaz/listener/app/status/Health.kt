package com.varizpardaz.listener.app.status

/** What the status screen checks. The order here is the order on screen. */
enum class ChecklistId { RECEIVE_SMS, READ_SMS, POST_NOTIFICATIONS, BATTERY, BACKGROUND_RESTRICTION, UNUSED_APP_PAUSE, MAKER_STEPS, NOTIFICATION_ACCESS }

/** [required] items keep the overall status at "needs action" until they are done; the others are only recommended. */
class ChecklistItem(val id: ChecklistId, val done: Boolean, val required: Boolean)

/** Everything the checklist is built from, read from the system by the app and passed in as plain values so the rules stay testable. */
class SystemFacts(
    val receiveSms: Boolean,
    val readSms: Boolean,
    val postNotifications: Boolean,
    val batteryExempt: Boolean,
    /** The user (or the maker) set the app to "Restricted" in its battery settings: Android then stops its service and does not restart it. */
    val backgroundRestricted: Boolean,
    /** `null`: this Android has no "pause app activity if unused" setting (Android 10 and older). */
    val exemptFromUnusedAppPause: Boolean?,
    /** `null`: this phone needs no maker-specific steps. Otherwise whether the user said they did them (Android cannot tell). */
    val makerStepsConfirmed: Boolean?,
    val notificationSourceEnabled: Boolean,
    val notificationAccess: Boolean,
)

object Checklist {
    fun build(facts: SystemFacts): List<ChecklistItem> = buildList {
        add(ChecklistItem(ChecklistId.RECEIVE_SMS, facts.receiveSms, required = true))
        // Without it a message missed while the phone was off or killed cannot be recovered from the inbox.
        add(ChecklistItem(ChecklistId.READ_SMS, facts.readSms, required = true))
        // The service runs without the notification; only its visible sign of life is lost.
        add(ChecklistItem(ChecklistId.POST_NOTIFICATIONS, facts.postNotifications, required = false))
        add(ChecklistItem(ChecklistId.BATTERY, facts.batteryExempt, required = true))
        add(ChecklistItem(ChecklistId.BACKGROUND_RESTRICTION, !facts.backgroundRestricted, required = true))
        facts.exemptFromUnusedAppPause?.let { add(ChecklistItem(ChecklistId.UNUSED_APP_PAUSE, it, required = true)) }
        facts.makerStepsConfirmed?.let { add(ChecklistItem(ChecklistId.MAKER_STEPS, it, required = true)) }
        // Only a question once the user has opted in to reading bank-app notifications.
        if (facts.notificationSourceEnabled) add(ChecklistItem(ChecklistId.NOTIFICATION_ACCESS, facts.notificationAccess, required = true))
    }
}

enum class LinkState { CONNECTING, UP, DOWN }

object Link {
    /** The server marks a device offline after two minutes without a heartbeat. */
    const val TIMEOUT_MS = 2 * 60 * 1000L

    /** After the app process starts, the first heartbeat may take a moment; do not call that "down" straight away. */
    const val CONNECTING_GRACE_MS = 90 * 1000L

    fun state(lastHeartbeatMillis: Long?, nowMillis: Long, processStartedMillis: Long): LinkState = when {
        lastHeartbeatMillis != null -> if (nowMillis - lastHeartbeatMillis < TIMEOUT_MS) LinkState.UP else LinkState.DOWN
        nowMillis - processStartedMillis < CONNECTING_GRACE_MS -> LinkState.CONNECTING
        else -> LinkState.DOWN
    }
}

enum class Health { ACTIVE, NEEDS_ACTION, CONNECTING, DOWN }

object Overall {
    fun of(link: LinkState, items: List<ChecklistItem>): Health = when (link) {
        LinkState.DOWN -> Health.DOWN
        LinkState.CONNECTING -> Health.CONNECTING
        LinkState.UP -> if (items.any { it.required && !it.done }) Health.NEEDS_ACTION else Health.ACTIVE
    }
}

enum class AgeUnit { NEVER, JUST_NOW, SECONDS, MINUTES, HOURS, DAYS }

/** "How long ago", coarse on purpose. The screen turns it into Persian text. */
class Age(val unit: AgeUnit, val count: Long = 0)

object Ages {
    fun of(thenMillis: Long?, nowMillis: Long): Age {
        if (thenMillis == null || thenMillis <= 0) return Age(AgeUnit.NEVER)
        val seconds = (nowMillis - thenMillis) / 1000
        return when {
            seconds < 5 -> Age(AgeUnit.JUST_NOW) // also a clock that is slightly ahead
            seconds < 60 -> Age(AgeUnit.SECONDS, seconds)
            seconds < 3600 -> Age(AgeUnit.MINUTES, seconds / 60)
            seconds < 86_400 -> Age(AgeUnit.HOURS, seconds / 3600)
            else -> Age(AgeUnit.DAYS, seconds / 86_400)
        }
    }
}
