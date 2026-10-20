package com.beiqi.counter

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.*

class CounterPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("beiqi_counter_prefs", Context.MODE_PRIVATE)

    companion object {
        const val THEME_OBSIDIAN = "Obsidian"
        const val THEME_GOLD = "Spiritual Gold"
        const val THEME_OCEAN = "Ocean Blue"
        const val THEME_EMERALD = "Emerald Forest"
        const val THEME_LOTUS = "Lotus Blossom"
    }

    private fun getTodayDateString(): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
    }

    private fun checkAndResetDaily() {
        val today = getTodayDateString()
        val lastDate = prefs.getString("last_recorded_date", "")
        if (lastDate != today) {
            prefs.edit()
                .putString("last_recorded_date", today)
                .putInt("today_count", 0)
                .apply()
        }
    }

    fun registerClick(malaTarget: Int = 108): MalaClickResult {
        checkAndResetDaily()

        var currentMalaCount = getCurrentMalaProgress() + 1
        var totalMalas = getTotalMalas()
        var malaCompleted = false

        if (currentMalaCount >= malaTarget) {
            currentMalaCount = 0
            totalMalas += 1
            malaCompleted = true
        }

        val todayCount = getTodayCount() + 1
        val lifetimeCount = getLifetimeCount() + 1

        prefs.edit()
            .putInt("current_mala_count", currentMalaCount)
            .putInt("total_malas", totalMalas)
            .putInt("today_count", todayCount)
            .putLong("lifetime_count", lifetimeCount)
            .apply()

        return MalaClickResult(
            currentMalaProgress = currentMalaCount,
            totalMalas = totalMalas,
            todayCount = todayCount,
            lifetimeCount = lifetimeCount,
            malaCompleted = malaCompleted
        )
    }

    fun syncFromDeviceCount(deviceCount: Int, malaTarget: Int = 108) {
        checkAndResetDaily()
        val previousToday = getTodayCount()
        val diff = if (deviceCount >= previousToday) deviceCount - previousToday else 0

        val newLifetime = getLifetimeCount() + diff
        val newToday = deviceCount
        val newMalas = newToday / malaTarget
        val newMalaProgress = newToday % malaTarget

        prefs.edit()
            .putInt("today_count", newToday)
            .putLong("lifetime_count", newLifetime)
            .putInt("total_malas", newMalas)
            .putInt("current_mala_count", newMalaProgress)
            .apply()
    }

    fun resetTodayAndMala() {
        prefs.edit()
            .putInt("current_mala_count", 0)
            .putInt("total_malas", 0)
            .putInt("today_count", 0)
            .apply()
    }

    fun resetAllLifetime() {
        prefs.edit().clear().apply()
        checkAndResetDaily()
    }

    fun getCurrentMalaProgress(): Int = prefs.getInt("current_mala_count", 0)
    fun getTotalMalas(): Int = prefs.getInt("total_malas", 0)
    fun getTodayCount(): Int {
        checkAndResetDaily()
        return prefs.getInt("today_count", 0)
    }
    fun getLifetimeCount(): Long = prefs.getLong("lifetime_count", 0L)

    // Settings
    var vibrateOnClick: Boolean
        get() = prefs.getBoolean("vibrate_on_click", true)
        set(value) = prefs.edit().putBoolean("vibrate_on_click", value).apply()

    var vibrateOnMala: Boolean
        get() = prefs.getBoolean("vibrate_on_mala", true)
        set(value) = prefs.edit().putBoolean("vibrate_on_mala", value).apply()

    var malaTargetSize: Int
        get() = prefs.getInt("mala_target_size", 108)
        set(value) = prefs.edit().putInt("mala_target_size", value).apply()

    var currentTheme: String
        get() = prefs.getString("current_theme", THEME_GOLD) ?: THEME_GOLD
        set(value) = prefs.edit().putString("current_theme", value).apply()

    data class MalaClickResult(
        val currentMalaProgress: Int,
        val totalMalas: Int,
        val todayCount: Int,
        val lifetimeCount: Long,
        val malaCompleted: Boolean
    )
}
