package com.beiqi.counter

import org.json.JSONObject
import java.util.*

data class CounterProfile(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    var imageUri: String? = null,
    var currentBeads: Int = 0,
    var dailyCount: Int = 0,
    var dailyMala: Int = 0,
    var lifetimeCount: Long = 0L,
    var lifetimeMala: Long = 0L,
    var lastDate: String = ""
) {
    fun checkAndResetDaily(today: String) {
        if (lastDate != today) {
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
        if (currentBeads >= 108) {
            currentBeads = 0
            dailyMala++
            lifetimeMala++
            malaCompleted = true
        }

        return malaCompleted
    }

    fun syncFromHardwareCount(ringCount: Int, today: String) {
        checkAndResetDaily(today)
        val diff = if (ringCount >= dailyCount) ringCount - dailyCount else 0

        dailyCount = ringCount
        dailyMala = ringCount / 108
        currentBeads = ringCount % 108

        lifetimeCount += diff
        lifetimeMala = (lifetimeCount / 108)
    }

    fun resetMalaOnly() {
        currentBeads = 0
    }

    fun toJsonObject(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("name", name)
            put("imageUri", imageUri ?: "")
            put("currentBeads", currentBeads)
            put("dailyCount", dailyCount)
            put("dailyMala", dailyMala)
            put("lifetimeCount", lifetimeCount)
            put("lifetimeMala", lifetimeMala)
            put("lastDate", lastDate)
        }
    }

    companion object {
        fun fromJsonObject(json: JSONObject): CounterProfile {
            return CounterProfile(
                id = json.optString("id", UUID.randomUUID().toString()),
                name = json.optString("name", "Counter"),
                imageUri = json.optString("imageUri", "").takeIf { it.isNotEmpty() },
                currentBeads = json.optInt("currentBeads", 0),
                dailyCount = json.optInt("dailyCount", 0),
                dailyMala = json.optInt("dailyMala", 0),
                lifetimeCount = json.optLong("lifetimeCount", 0L),
                lifetimeMala = json.optLong("lifetimeMala", 0L),
                lastDate = json.optString("lastDate", "")
            )
        }
    }
}
