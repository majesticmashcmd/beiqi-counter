package com.beiqi.counter

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.*

class CounterRepository(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("beiqi_multicounter_prefs", Context.MODE_PRIVATE)

    companion object {
        const val THEME_OBSIDIAN = "Obsidian Blue"
        const val THEME_GOLD = "Spiritual Gold"
        const val THEME_EMERALD = "Emerald Forest"
        const val THEME_LOTUS = "Lotus Blossom"
    }

    fun getTodayString(): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
    }

    fun getAllCounters(): MutableList<CounterProfile> {
        val jsonStr = prefs.getString("counters_list", null)
        val today = getTodayString()
        val list = mutableListOf<CounterProfile>()

        if (jsonStr.isNullOrEmpty()) {
            val meditation = CounterProfile(
                name = "Meditation (Japa)",
                mantra = "Om Namah Shivaya",
                lastDate = today,
                beadTarget = 108
            )
            val sewing = CounterProfile(
                name = "Sewing & Crafts",
                mantra = "Stitch Counter",
                lastDate = today,
                beadTarget = 108
            )
            list.add(meditation)
            list.add(sewing)
            saveAllCounters(list)
            setActiveCounterId(meditation.id)
            return list
        }

        try {
            val jsonArray = JSONArray(jsonStr)
            for (i in 0 until jsonArray.length()) {
                val item = CounterProfile.fromJsonObject(jsonArray.getJSONObject(i))
                item.checkAndResetDaily(today)
                list.add(item)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        if (list.isEmpty()) {
            val defaultCounter = CounterProfile(name = "Meditation (Japa)", mantra = "Om Namah Shivaya", lastDate = today)
            list.add(defaultCounter)
            saveAllCounters(list)
            setActiveCounterId(defaultCounter.id)
        }

        return list
    }

    fun saveAllCounters(list: List<CounterProfile>) {
        val jsonArray = JSONArray()
        for (item in list) {
            jsonArray.put(item.toJsonObject())
        }
        prefs.edit().putString("counters_list", jsonArray.toString()).apply()
    }

    fun getActiveCounter(): CounterProfile {
        val list = getAllCounters()
        val activeId = prefs.getString("active_counter_id", null)
        val found = list.find { it.id == activeId } ?: list.first()
        found.checkAndResetDaily(getTodayString())
        return found
    }

    fun saveActiveCounter(updated: CounterProfile) {
        val list = getAllCounters()
        val index = list.indexOfFirst { it.id == updated.id }
        if (index != -1) {
            list[index] = updated
        } else {
            list.add(updated)
        }
        saveAllCounters(list)
    }

    fun addCounter(name: String, mantra: String = "", beadTarget: Int = 108, imageUri: String? = null): CounterProfile {
        val list = getAllCounters()
        val newProfile = CounterProfile(
            name = name,
            mantra = mantra,
            imageUri = imageUri,
            beadTarget = beadTarget,
            lastDate = getTodayString()
        )
        list.add(newProfile)
        saveAllCounters(list)
        setActiveCounterId(newProfile.id)
        return newProfile
    }

    fun deleteCounter(id: String): CounterProfile? {
        val list = getAllCounters()
        if (list.size <= 1) return null

        list.removeAll { it.id == id }
        saveAllCounters(list)
        val newActive = list.first()
        setActiveCounterId(newActive.id)
        return newActive
    }

    fun setActiveCounterId(id: String) {
        prefs.edit().putString("active_counter_id", id).apply()
    }

    fun recordClick(): Pair<CounterProfile, Boolean> {
        val active = getActiveCounter()
        val malaCompleted = active.registerClick(getTodayString())
        saveActiveCounter(active)
        return Pair(active, malaCompleted)
    }

    fun undoClick(): CounterProfile {
        val active = getActiveCounter()
        active.undoClick(getTodayString())
        saveActiveCounter(active)
        return active
    }

    fun syncActiveCounterWithRing(ringCount: Int): CounterProfile {
        val active = getActiveCounter()
        active.syncFromHardwareCount(ringCount, getTodayString())
        saveActiveCounter(active)
        return active
    }

    var vibrateOnClick: Boolean
        get() = prefs.getBoolean("vibrate_on_click", true)
        set(value) = prefs.edit().putBoolean("vibrate_on_click", value).apply()

    var vibrateOnMala: Boolean
        get() = prefs.getBoolean("vibrate_on_mala", true)
        set(value) = prefs.edit().putBoolean("vibrate_on_mala", value).apply()

    var soundOnMala: Boolean
        get() = prefs.getBoolean("sound_on_mala", true)
        set(value) = prefs.edit().putBoolean("sound_on_mala", value).apply()

    var soundOnClick: Boolean
        get() = prefs.getBoolean("sound_on_click", false)
        set(value) = prefs.edit().putBoolean("sound_on_click", value).apply()

    var keepScreenAwake: Boolean
        get() = prefs.getBoolean("keep_screen_awake", false)
        set(value) = prefs.edit().putBoolean("keep_screen_awake", value).apply()

    var currentTheme: String
        get() = prefs.getString("current_theme", THEME_GOLD) ?: THEME_GOLD
        set(value) = prefs.edit().putString("current_theme", value).apply()

    // Calibration: Characteristic UUID and Packet Signature of middle button
    var calibratedCharUuid: String?
        get() = prefs.getString("calibrated_char_uuid", null)
        set(value) = prefs.edit().putString("calibrated_char_uuid", value).apply()

    var calibratedSignature: String?
        get() = prefs.getString("calibrated_signature", null)
        set(value) = prefs.edit().putString("calibrated_signature", value).apply()

    fun clearCalibration() {
        prefs.edit()
            .remove("calibrated_char_uuid")
            .remove("calibrated_signature")
            .apply()
    }
}
