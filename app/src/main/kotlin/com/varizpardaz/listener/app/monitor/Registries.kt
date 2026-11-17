package com.varizpardaz.listener.app.monitor

import android.content.SharedPreferences
import androidx.core.content.edit
import com.varizpardaz.listener.core.rescan.RescanLedger
import com.varizpardaz.listener.core.wire.SimInfo
import org.json.JSONException
import org.json.JSONObject

/**
 * SIMs the phone has seen: remembered from the slot and subscription id that arrive with each SMS, so the heartbeat can
 * report `sims` and a rescan can be narrowed to one SIM without needing the phone-state permission.
 */
class SimRegistry(private val prefs: SharedPreferences) {
    fun remember(slot: Int?, subscriptionId: Int?) {
        if (slot == null || subscriptionId == null) return
        val entry = "$slot:$subscriptionId"
        val known = prefs.getStringSet(KEY, emptySet()).orEmpty()
        if (entry !in known) prefs.edit { putStringSet(KEY, known + entry) }
    }

    fun all(): List<SimInfo> = prefs.getStringSet(KEY, emptySet()).orEmpty().mapNotNull { entry ->
        val (slot, sub) = entry.split(':').mapNotNull(String::toIntOrNull).takeIf { it.size == 2 } ?: return@mapNotNull null
        SimInfo(slot = slot, subscriptionId = sub)
    }.sortedBy { it.slot }

    fun subscriptionOfSlot(slot: Int): Int? = all().lastOrNull { it.slot == slot }?.subscriptionId

    fun slotOfSubscription(subscriptionId: Int): Int? = all().lastOrNull { it.subscriptionId == subscriptionId }?.slot

    fun clear() = prefs.edit(commit = true) { remove(KEY) }

    private companion object {
        const val KEY = "sims"
    }
}

/** [RescanLedger] in preferences: request id to the number found, until the server stops listing the request. */
class PrefsRescanLedger(private val prefs: SharedPreferences) : RescanLedger {
    @Synchronized
    override fun doneEntries(): Map<String, Int> =
        try {
            val json = JSONObject(prefs.getString(KEY, "{}").orEmpty())
            json.keys().asSequence().associateWith { json.getInt(it) }
        } catch (_: JSONException) {
            emptyMap()
        }

    @Synchronized
    override fun record(requestId: String, found: Int) = write(doneEntries() + (requestId to found))

    @Synchronized
    override fun forget(requestIds: Collection<String>) = write(doneEntries() - requestIds.toSet())

    fun clear() = prefs.edit(commit = true) { remove(KEY) }

    private fun write(entries: Map<String, Int>) {
        val json = JSONObject()
        entries.forEach { (id, found) -> json.put(id, found) }
        prefs.edit { putString(KEY, json.toString()) }
    }

    private companion object {
        const val KEY = "rescans_done"
    }
}
