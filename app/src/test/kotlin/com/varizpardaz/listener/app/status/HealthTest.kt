package com.varizpardaz.listener.app.status

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HealthTest {
    private fun facts(
        receiveSms: Boolean = true,
        readSms: Boolean = true,
        postNotifications: Boolean = true,
        batteryExempt: Boolean = true,
        backgroundRestricted: Boolean = false,
        exemptFromUnusedAppPause: Boolean? = true,
        makerStepsConfirmed: Boolean? = true,
        notificationSourceEnabled: Boolean = false,
        notificationAccess: Boolean = false,
    ) = SystemFacts(
        receiveSms, readSms, postNotifications, batteryExempt, backgroundRestricted, exemptFromUnusedAppPause, makerStepsConfirmed,
        notificationSourceEnabled, notificationAccess,
    )

    private fun ids(facts: SystemFacts) = Checklist.build(facts).map { it.id }

    @Test
    fun `a fully set up phone has a checklist with nothing left to do`() {
        val items = Checklist.build(facts())
        assertEquals(
            listOf(
                ChecklistId.RECEIVE_SMS, ChecklistId.READ_SMS, ChecklistId.POST_NOTIFICATIONS,
                ChecklistId.BATTERY, ChecklistId.BACKGROUND_RESTRICTION, ChecklistId.UNUSED_APP_PAUSE, ChecklistId.MAKER_STEPS,
            ),
            items.map { it.id },
        )
        assertTrue(items.all { it.done })
    }

    @Test
    fun `items that do not apply to this phone are left out`() {
        assertFalse(ChecklistId.UNUSED_APP_PAUSE in ids(facts(exemptFromUnusedAppPause = null)), "Android 10 and older have no such setting")
        assertFalse(ChecklistId.MAKER_STEPS in ids(facts(makerStepsConfirmed = null)), "a phone with no maker layer has no maker steps")
        assertFalse(ChecklistId.NOTIFICATION_ACCESS in ids(facts()), "the notification source is opt-in")
        assertTrue(ChecklistId.NOTIFICATION_ACCESS in ids(facts(notificationSourceEnabled = true)))
    }

    @Test
    fun `notification access only counts as missing once the user opted in`() {
        val missing = Checklist.build(facts(notificationSourceEnabled = true, notificationAccess = false)).single { it.id == ChecklistId.NOTIFICATION_ACCESS }
        assertFalse(missing.done)
        assertTrue(missing.required)
        assertTrue(Checklist.build(facts(notificationSourceEnabled = true, notificationAccess = true)).single { it.id == ChecklistId.NOTIFICATION_ACCESS }.done)
    }

    @Test
    fun `the service notification is only recommended, everything that can stop the app silently is required`() {
        val items = Checklist.build(facts(notificationSourceEnabled = true)).associateBy { it.id }
        assertFalse(items.getValue(ChecklistId.POST_NOTIFICATIONS).required)
        listOf(ChecklistId.RECEIVE_SMS, ChecklistId.READ_SMS, ChecklistId.BATTERY, ChecklistId.BACKGROUND_RESTRICTION, ChecklistId.UNUSED_APP_PAUSE, ChecklistId.MAKER_STEPS, ChecklistId.NOTIFICATION_ACCESS)
            .forEach { assertTrue(items.getValue(it).required, "$it") }
    }

    @Test
    fun `a phone set to Restricted in its battery settings has an open required row, even with the exemption granted`() {
        val items = Checklist.build(facts(batteryExempt = true, backgroundRestricted = true)).associateBy { it.id }
        assertFalse(items.getValue(ChecklistId.BACKGROUND_RESTRICTION).done)
        assertTrue(items.getValue(ChecklistId.BACKGROUND_RESTRICTION).required)
        assertEquals(Health.NEEDS_ACTION, Overall.of(LinkState.UP, items.values.toList()))
        assertTrue(Checklist.build(facts(backgroundRestricted = false)).single { it.id == ChecklistId.BACKGROUND_RESTRICTION }.done)
    }

    @Test
    fun `overall status follows the link first, then the required items`() {
        val ok = Checklist.build(facts())
        val missingRequired = Checklist.build(facts(batteryExempt = false))
        val missingRecommended = Checklist.build(facts(postNotifications = false))

        assertEquals(Health.ACTIVE, Overall.of(LinkState.UP, ok))
        assertEquals(Health.NEEDS_ACTION, Overall.of(LinkState.UP, missingRequired))
        assertEquals(Health.ACTIVE, Overall.of(LinkState.UP, missingRecommended))
        assertEquals(Health.DOWN, Overall.of(LinkState.DOWN, ok))
        assertEquals(Health.CONNECTING, Overall.of(LinkState.CONNECTING, ok))
    }

    @Test
    fun `link state uses the two-minute server rule, with a grace period after the process starts`() {
        val start = 1_000_000L
        assertEquals(LinkState.CONNECTING, Link.state(null, start + 30_000, start))
        assertEquals(LinkState.DOWN, Link.state(null, start + Link.CONNECTING_GRACE_MS, start))
        assertEquals(LinkState.UP, Link.state(start + 10_000, start + 60_000, start))
        assertEquals(LinkState.UP, Link.state(start, start + Link.TIMEOUT_MS - 1, start))
        assertEquals(LinkState.DOWN, Link.state(start, start + Link.TIMEOUT_MS, start))
        // A heartbeat that stops stays "down" even right after a restart: the old time says so.
        assertEquals(LinkState.DOWN, Link.state(start - Link.TIMEOUT_MS, start + 1_000, start))
    }

    @Test
    fun `ages are coarse and never negative`() {
        val now = 10_000_000_000L
        assertEquals(AgeUnit.NEVER, Ages.of(null, now).unit)
        assertEquals(AgeUnit.NEVER, Ages.of(0, now).unit)
        assertEquals(AgeUnit.JUST_NOW, Ages.of(now - 2_000, now).unit)
        assertEquals(AgeUnit.JUST_NOW, Ages.of(now + 60_000, now).unit, "a clock slightly ahead must not show a negative age")
        assertEquals(12L, Ages.of(now - 12_000, now).count)
        assertEquals(AgeUnit.SECONDS, Ages.of(now - 59_000, now).unit)
        assertEquals(AgeUnit.MINUTES, Ages.of(now - 60_000, now).unit)
        assertEquals(5L, Ages.of(now - 5 * 60_000 - 10_000, now).count)
        assertEquals(AgeUnit.HOURS, Ages.of(now - 2 * 3_600_000L, now).unit)
        assertEquals(AgeUnit.DAYS, Ages.of(now - 3 * 86_400_000L, now).unit)
        assertEquals(3L, Ages.of(now - 3 * 86_400_000L, now).count)
    }
}
