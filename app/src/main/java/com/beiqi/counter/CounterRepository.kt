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

        // Save daily history for weekly chart
        val today = getTodayString()
        prefs.edit().putInt("hist_${updated.id}_$today", updated.dailyMala).apply()
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

    fun recordClick(): ClickResult {
        val active = getActiveCounter()
        val result = active.registerClick(getTodayString())
        saveActiveCounter(active)
        return result
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

    // Weekly history (last 7 days)
    fun getWeeklyHistory(counterId: String): List<DayHistoryItem> {
        val list = mutableListOf<DayHistoryItem>()
        val cal = Calendar.getInstance()
        val keyFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val dayLabelFormat = SimpleDateFormat("EEE", Locale.getDefault())

        val active = getActiveCounter()
        val todayStr = keyFormat.format(Date())

        for (i in 6 downTo 0) {
            val dateCal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -i) }
            val key = keyFormat.format(dateCal.time)
            val dayLabel = dayLabelFormat.format(dateCal.time)

            val malas = if (key == todayStr) {
                active.dailyMala
            } else {
                prefs.getInt("hist_${counterId}_$key", 0)
            }
            list.add(DayHistoryItem(dayLabel = dayLabel, dateKey = key, malas = malas, isToday = (i == 0)))
        }
        return list
    }

    fun generateShareText(): String {
        val active = getActiveCounter()
        val today = getTodayString()
        return buildString {
            append("📿 Daily Japa Sadhana Summary\n")
            append("✨ Counter: ${active.name}\n")
            if (active.mantra.isNotEmpty()) {
                append("🕉️ Mantra: ${active.mantra}\n")
            }
            append("📅 Date: $today\n")
            append("────────────────────\n")
            append("📿 Beads Today: %,d (%d Malas)\n".format(active.dailyCount, active.dailyMala))
            append("🏆 Lifetime Beads: %,d (%d Malas)\n".format(active.lifetimeCount, active.lifetimeMala))
            append("🔥 Chanting Streak: ${active.streakDays} Days\n")
            append("⏱️ Duration: ${active.getFormattedDuration()}\n")
            append("────────────────────\n")
            append("Chanted with BEIQI-ST Smart Ring on Daily Japa 🙏")
        }
    }

    var vibrateOnClick: Boolean
        get() = prefs.getBoolean("vibrate_on_click", true)
        set(value) = prefs.edit().putBoolean("vibrate_on_click", value).apply()

    var vibrateOnMala: Boolean
        get() = prefs.getBoolean("vibrate_on_mala", true)
        set(value) = prefs.edit().putBoolean("vibrate_on_mala", value).apply()

    var vibrateOnQuarterMala: Boolean
        get() = prefs.getBoolean("vibrate_on_quarter", true)
        set(value) = prefs.edit().putBoolean("vibrate_on_quarter", value).apply()

    var soundOnMala: Boolean
        get() = prefs.getBoolean("sound_on_mala", true)
        set(value) = prefs.edit().putBoolean("sound_on_mala", value).apply()

    var soundOnClick: Boolean
        get() = prefs.getBoolean("sound_on_click", false)
        set(value) = prefs.edit().putBoolean("sound_on_click", value).apply()

    var keepScreenAwake: Boolean
        get() = prefs.getBoolean("keep_screen_awake", false)
        set(value) = prefs.edit().putBoolean("keep_screen_awake", value).apply()

    var backgroundCountingEnabled: Boolean
        get() = prefs.getBoolean("bg_counting_enabled", true)
        set(value) = prefs.edit().putBoolean("bg_counting_enabled", value).apply()

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

data class DayHistoryItem(
    val dayLabel: String,
    val dateKey: String,
    val malas: Int,
    val isToday: Boolean
)
