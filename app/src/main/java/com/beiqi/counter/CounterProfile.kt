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
    var streakDays: Int = 1
) {
    fun checkAndResetDaily(today: String) {
        if (lastDate.isEmpty()) {
            lastDate = today
            streakDays = 1
            return
        }

        if (lastDate != today) {
            // Check if lastDate was yesterday to update streak
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
            lastDate = today
        }
    }

    fun registerClick(today: String): Boolean {
        checkAndResetDaily(today)

        dailyCount++
        lifetimeCount++
        currentBeads++

        var malaCompleted = false
        if (currentBeads >= beadTarget) {
            currentBeads = 0
            dailyMala++
            lifetimeMala++
            malaCompleted = true
        }

        return malaCompleted
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
            currentBeads = beadTarget - 1
        }
    }

    fun syncFromHardwareCount(ringCount: Int, today: String) {
        checkAndResetDaily(today)
        val diff = if (ringCount >= dailyCount) ringCount - dailyCount else 0

        dailyCount = ringCount
        dailyMala = ringCount / beadTarget
        currentBeads = ringCount % beadTarget

        lifetimeCount += diff
        lifetimeMala = (lifetimeCount / beadTarget)
    }

    fun resetMalaOnly() {
        currentBeads = 0
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
                streakDays = json.optInt("streakDays", 1)
            )
        }
    }
}
