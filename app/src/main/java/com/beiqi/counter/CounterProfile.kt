package com.beiqi.counter

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

data class CounterProfile(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    var mantra: String = "",
    var imageUri: String? = null,
    var currentBeads: Int = 0,
    var dailyCount: Int = 0,
    var dailyMala: Int = 0,
    var lifetimeCount: Long = 0L,
    var lifetimeMala: Long = 0L,
    var lastDate: String = "",
    var beadTarget: Int = 108,
    var streakDays: Int = 1,
    var todayChantSeconds: Long = 0L,
    var lastClickTimestamp: Long = 0L
) {
    fun checkAndResetDaily(today: String) {
        if (lastDate.isEmpty()) {
            lastDate = today
            streakDays = 1
            return
        }

        if (lastDate != today) {
            try {
                val format = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                val lastTime = format.parse(lastDate)?.time ?: 0L
                val todayTime = format.parse(today)?.time ?: 0L
                val diffDays = (todayTime - lastTime) / (1000 * 60 * 60 * 24)
                if (diffDays == 1L) {
                    streakDays += 1
                } else if (diffDays > 1L) {
                    streakDays = 1
                }
            } catch (e: Exception) {
                streakDays = 1
            }

            dailyCount = 0
            dailyMala = 0
            todayChantSeconds = 0L
            lastDate = today
        }
    }

    /**
     * Records a click and returns:
     * - malaCompleted: Boolean (true when full target is reached)
     * - milestoneQuarter: Int (1 for 25%, 2 for 50%, 3 for 75%, 4 for 100%, 0 otherwise)
     */
    fun registerClick(today: String): ClickResult {
        checkAndResetDaily(today)

        val now = System.currentTimeMillis()
        if (lastClickTimestamp > 0L) {
            val deltaSeconds = (now - lastClickTimestamp) / 1000L
            // If clicks are spaced within 15 seconds, count as active continuous chanting
            if (deltaSeconds in 1..15) {
                todayChantSeconds += deltaSeconds
            } else if (deltaSeconds > 15) {
                todayChantSeconds += 2 // small buffer for session resume
            }
        } else {
            todayChantSeconds += 2
        }
        lastClickTimestamp = now

        dailyCount++
        lifetimeCount++
        currentBeads++

        var malaCompleted = false
        var milestoneQuarter = 0

        val q1 = beadTarget * 1 / 4
        val q2 = beadTarget * 2 / 4
        val q3 = beadTarget * 3 / 4

        if (currentBeads >= beadTarget) {
            currentBeads = 0
            dailyMala++
            lifetimeMala++
            malaCompleted = true
            milestoneQuarter = 4
        } else if (currentBeads == q1) {
            milestoneQuarter = 1
        } else if (currentBeads == q2) {
            milestoneQuarter = 2
        } else if (currentBeads == q3) {
            milestoneQuarter = 3
        }

        return ClickResult(
            counter = this,
            malaCompleted = malaCompleted,
            milestoneQuarter = milestoneQuarter
        )
    }

    fun undoClick(today: String) {
        checkAndResetDaily(today)
        if (dailyCount > 0) dailyCount--
        if (lifetimeCount > 0) lifetimeCount--

        if (currentBeads > 0) {
            currentBeads--
        } else if (dailyMala > 0) {
            dailyMala--
            if (lifetimeMala > 0) lifetimeMala--
            currentBeads = (beadTarget - 1).coerceAtLeast(0)
        }
    }

    fun syncFromHardwareCount(ringCount: Int, today: String) {
        checkAndResetDaily(today)
        val diff = if (ringCount >= dailyCount) ringCount - dailyCount else 0

        dailyCount = ringCount
        dailyMala = if (beadTarget > 0) ringCount / beadTarget else 0
        currentBeads = if (beadTarget > 0) ringCount % beadTarget else 0

        lifetimeCount += diff
        lifetimeMala = if (beadTarget > 0) (lifetimeCount / beadTarget) else 0L
    }

    fun resetMalaOnly() {
        currentBeads = 0
    }

    fun getPaceDescription(): String {
        if (dailyCount < 5 || todayChantSeconds < 10) {
            return "Pacing calculating..."
        }
        val mins = todayChantSeconds / 60.0
        val beadsPerMin = (dailyCount / mins).toInt()
        val minsPerMala = if (beadsPerMin > 0) beadTarget.toDouble() / beadsPerMin else 0.0
        return String.format(Locale.getDefault(), "⏱️ ~%.1fm / Mala • %d beads/min", minsPerMala, beadsPerMin)
    }

    fun getFormattedDuration(): String {
        val totalSecs = todayChantSeconds
        val mins = totalSecs / 60
        val secs = totalSecs % 60
        return if (mins >= 60) {
            val hours = mins / 60
            val remMins = mins % 60
            "${hours}h ${remMins}m today"
        } else {
            "${mins}m ${secs}s today"
        }
    }

    fun toJsonObject(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("name", name)
            put("mantra", mantra)
            put("imageUri", imageUri ?: "")
            put("currentBeads", currentBeads)
            put("dailyCount", dailyCount)
            put("dailyMala", dailyMala)
            put("lifetimeCount", lifetimeCount)
            put("lifetimeMala", lifetimeMala)
            put("lastDate", lastDate)
            put("beadTarget", beadTarget)
            put("streakDays", streakDays)
            put("todayChantSeconds", todayChantSeconds)
            put("lastClickTimestamp", lastClickTimestamp)
        }
    }

    companion object {
        fun fromJsonObject(json: JSONObject): CounterProfile {
            return CounterProfile(
                id = json.optString("id", UUID.randomUUID().toString()),
                name = json.optString("name", "Counter"),
                mantra = json.optString("mantra", ""),
                imageUri = json.optString("imageUri", "").takeIf { it.isNotEmpty() },
                currentBeads = json.optInt("currentBeads", 0),
                dailyCount = json.optInt("dailyCount", 0),
                dailyMala = json.optInt("dailyMala", 0),
                lifetimeCount = json.optLong("lifetimeCount", 0L),
                lifetimeMala = json.optLong("lifetimeMala", 0L),
                lastDate = json.optString("lastDate", ""),
                beadTarget = json.optInt("beadTarget", 108),
                streakDays = json.optInt("streakDays", 1),
                todayChantSeconds = json.optLong("todayChantSeconds", 0L),
                lastClickTimestamp = json.optLong("lastClickTimestamp", 0L)
            )
        }
    }
}

data class ClickResult(
    val counter: CounterProfile,
    val malaCompleted: Boolean,
    val milestoneQuarter: Int // 1 (25%), 2 (50%), 3 (75%), 4 (100% Mala), 0 otherwise
)
