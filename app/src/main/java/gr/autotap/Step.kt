package gr.autotap

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Ένα βήμα: πάτημα (TAP) ή τράβηγμα δαχτύλου (SWIPE). Συντεταγμένες σε pixel οθόνης. */
data class Step(
    var type: Type,
    var x: Float,
    var y: Float,
    var x2: Float = x,
    var y2: Float = y,
    var delayMs: Long = 500,      // Χρόνος καθυστέρησης πριν το βήμα
    var repeat: Int = 1,          // Αριθμός επαναλήψεων του βήματος
    var durationMs: Long = 50,    // Διάρκεια πατήματος / συρσίματος
    var antiDetect: Boolean = false
) {
    enum class Type { TAP, SWIPE }

    fun toJson() = JSONObject().apply {
        put("type", type.name); put("x", x.toDouble()); put("y", y.toDouble())
        put("x2", x2.toDouble()); put("y2", y2.toDouble())
        put("delay", delayMs); put("repeat", repeat); put("dur", durationMs)
        put("anti", antiDetect)
    }

    companion object {
        fun fromJson(o: JSONObject) = Step(
            Type.valueOf(o.getString("type")),
            o.getDouble("x").toFloat(), o.getDouble("y").toFloat(),
            o.getDouble("x2").toFloat(), o.getDouble("y2").toFloat(),
            o.getLong("delay"), o.getInt("repeat"), o.getLong("dur"),
            o.optBoolean("anti", false)
        )
    }
}

/** Αποθηκευμένες διαμορφώσεις ("Οι διαμορφώσεις μου"). */
object ConfigStore {
    private const val PREFS = "autotap_configs"

    data class Config(val name: String, val savedAt: Long, val loops: Int, val steps: List<Step>)

    fun save(ctx: Context, name: String, loops: Int, steps: List<Step>) {
        val arr = JSONArray(); steps.forEach { arr.put(it.toJson()) }
        val o = JSONObject().put("savedAt", System.currentTimeMillis())
            .put("loops", loops).put("steps", arr)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(name, o.toString()).apply()
    }

    fun list(ctx: Context): List<Config> =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all.mapNotNull { (name, v) ->
            runCatching {
                val o = JSONObject(v as String)
                val arr = o.getJSONArray("steps")
                Config(name, o.getLong("savedAt"), o.optInt("loops", 1),
                    (0 until arr.length()).map { Step.fromJson(arr.getJSONObject(it)) })
            }.getOrNull()
        }.sortedByDescending { it.savedAt }

    fun delete(ctx: Context, name: String) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(name).apply()
}
