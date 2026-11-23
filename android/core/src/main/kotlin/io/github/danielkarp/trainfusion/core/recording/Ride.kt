package io.github.danielkarp.trainfusion.core.recording

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * One ride: its Train Measurements (timings, and what was typed in about the train), kept
 * after its recording is deleted from the phone; what's shared (docs/api.md,
 * "Measurements"). As ios/TrainRecorder/RecordingStore.swift's `Ride`.
 */
@Serializable
data class Ride(
    val id: String,
    val started: Double? = null,
    val ended: Double? = null,
    val stoppedProperly: Boolean = false,
    val appVersion: String? = null,
    val train: Train? = null,
    val direction: String? = null,
    val stationConfidence: Double? = null,
    val stops: List<Stop> = emptyList(),
    val upload: Upload = Upload.UNDECIDED,
    /** When its Train Measurements were shared (unix seconds), if they are. */
    val sharedAt: Double? = null,
    /** To be shared as soon as possible (it ended with sharing on). */
    val sharePending: Boolean? = null,
) {
    @Serializable
    data class Stop(
        val station: String? = null,
        val doorsOpened: Double,  // unix seconds, NTP-corrected when the recording had NTP
        val doorsClosed: Double? = null,
    )

    @Serializable
    data class Train(val trip: Int, val car: Int, val position: String)

    /**
     * Whether the recording is to be uploaded. Settled (may be deleted from the phone):
     * uploaded, declined, tooLarge.
     */
    @Serializable
    enum class Upload {
        @SerialName("undecided") UNDECIDED,  // not asked yet (or from before uploads were asked about)
        @SerialName("declined") DECLINED,
        @SerialName("wanted") WANTED,  // queued or uploading
        @SerialName("uploaded") UPLOADED,
        @SerialName("tooLarge") TOO_LARGE,
    }

    val settled: Boolean get() = upload == Upload.UPLOADED || upload == Upload.DECLINED || upload == Upload.TOO_LARGE

    /** Where the model heard the ride start and end. */
    val title: String
        get() {
            val origin = stops.firstOrNull()?.station ?: return "Unknown trip"
            val destination = if (stops.size > 1) stops.last().station else null
            val sure = (stationConfidence ?: 0.0) > 0.9
            return origin + (destination?.let { " → $it" } ?: "") + if (sure) "" else " (unsure)"
        }

    /** The body of PUT /v1/measurements/<id>. */
    fun apiBody(): JsonObject = buildJsonObject {
        put("started_unix_s", started.json())
        put("ended_unix_s", ended.json())
        put("app_version", appVersion?.let(::JsonPrimitive) ?: JsonNull)
        put("train", train?.let {
            buildJsonObject { put("trip_number", it.trip); put("car_number", it.car); put("car_position", it.position) }
        } ?: JsonNull)
        put("direction", direction?.let(::JsonPrimitive) ?: JsonNull)
        put("station_confidence", stationConfidence.json())
        put("stops", buildJsonArray {
            stops.forEach { s ->
                add(buildJsonObject {
                    put("station", s.station?.let(::JsonPrimitive) ?: JsonNull)
                    put("doors_opened_unix_s", s.doorsOpened)
                    put("doors_closed_unix_s", s.doorsClosed.json())
                })
            }
        })
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun encodeList(rides: List<Ride>): String = json.encodeToString(ListSerializer(serializer()), rides)
        fun decodeList(text: String): List<Ride> = json.decodeFromString(ListSerializer(serializer()), text)

        /**
         * From a recording's meta.json, with its times converted from uptime to clock time
         * (docs/recorder.md, "Timestamps").
         */
        fun read(dir: File, id: String): Ride {
            val meta = try {
                Json.parseToJsonElement(File(dir, "meta.json").readText()).jsonObject
            } catch (_: Exception) {
                return Ride(id)
            }
            val startUptime = meta.num("started_uptime_s")
            val startUnix = meta.num("started_unix_s")
            val offset = ntpOffset(dir) ?: (if (startUnix != null && startUptime != null) startUnix - startUptime else null)
                ?: return Ride(id)
            val unix = { uptime: Double -> uptime + offset }
            var train: Train? = null
            (meta["ride"] as? JsonObject)?.let { t ->
                val trip = (t["trip_number"] as? JsonPrimitive)?.intOrNull
                val car = (t["car_number"] as? JsonPrimitive)?.intOrNull
                if (trip != null && car != null) train = Train(trip, car, (t["car_position"] as? JsonPrimitive)?.contentOrNull() ?: "unspecified")
            }
            var direction: String? = null
            var confidence: Double? = null
            var stops = emptyList<Stop>()
            (meta["inferred"] as? JsonObject)?.let { inferred ->
                direction = (inferred["direction"] as? JsonPrimitive)?.contentOrNull()
                confidence = inferred.num("station_confidence")
                stops = ((inferred["stops"] as? JsonArray) ?: JsonArray(emptyList())).mapNotNull { e ->
                    val s = e as? JsonObject ?: return@mapNotNull null
                    val opened = s.num("doors_opened_uptime_s") ?: return@mapNotNull null
                    Stop((s["station"] as? JsonPrimitive)?.contentOrNull(), unix(opened), s.num("doors_closed_uptime_s")?.let(unix))
                }
            }
            return Ride(
                id = id,
                started = startUptime?.let(unix),
                ended = meta.num("ended_uptime_s")?.let(unix),
                stoppedProperly = (meta["complete"] as? JsonPrimitive)?.booleanOrNull ?: false,
                appVersion = (meta["app_version"] as? JsonPrimitive)?.contentOrNull(),
                train = train,
                direction = direction,
                stationConfidence = confidence,
                stops = stops,
            )
        }

        /** true unix time − uptime, from the NTP exchange in clock.csv with the smallest round trip. */
        fun ntpOffset(dir: File): Double? {
            val text = try { File(dir, "clock.csv").readText() } catch (_: Exception) { return null }
            var best: Pair<Double, Double>? = null  // round trip, offset
            for (line in text.split("\n").drop(1)) {
                val f = line.split(",").map { it.toDoubleOrNull() }
                if (f.size < 4) continue
                val uptime = f[0] ?: continue
                val unix = f[1] ?: continue
                val ntp = f[2] ?: continue
                val roundTrip = f[3] ?: continue
                if (best == null || roundTrip < best.first) best = roundTrip to (unix + ntp - uptime)
            }
            return best?.second
        }
    }
}

/** How many recordings to keep on the phone (Settings). */
enum class KeepRecordings(val raw: String, val label: String, val count: Int?) {
    NONE("none", "Don't keep", 0),
    LAST3("last3", "Keep last 3", 3),
    ALL("all", "Keep all", null);

    companion object {
        fun of(raw: String?) = entries.firstOrNull { it.raw == raw } ?: LAST3
    }
}

/**
 * Which train and where on it, as the rider typed it in. Saved in meta.json; the model
 * doesn't look at it.
 */
data class RideInfo(val trip: Int, val car: Int, val position: Position) {
    enum class Position(val raw: String) { UNSPECIFIED("unspecified"), UPPER("upper"), LOWER("lower"), VESTIBULE("vestibule") }

    companion object {
        /**
         * How the timetable's trip numbers start: 1 local (weekdays), 4 limited, 5 express,
         * 6 local (weekends); and 9, which the iOS app allows too. (The iOS app has "1459",
         * which refuses weekend locals.) Not 8: South County trains are outside the project.
         * Not 3: car numbers start with 3, so a car number typed as the trip is still caught.
         */
        private const val TRIP_FIRST_DIGITS = "14569"

        /**
         * What's wrong with what was typed, or null. Deliberately loose: the exact sets of
         * trip and car numbers change with the timetable and the fleet.
         */
        fun problem(trip: String, car: String): String? {
            fun digits(text: String) = text.isNotEmpty() && text.all { it in '0'..'9' }
            if (!digits(trip) || trip.length != 3 || trip[0] !in TRIP_FIRST_DIGITS) return "A trip number has 3 digits and starts with 1, 4, 5, 6 or 9."
            if (!digits(car) || (car.length != 3 && car.length != 4) || car[0] != '3') return "A car number has 3 or 4 digits and starts with 3."
            return null
        }
    }
}

private fun Double?.json(): JsonElement = this?.let(::JsonPrimitive) ?: JsonNull
private fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
private fun JsonPrimitive.contentOrNull(): String? = if (this is JsonNull) null else content

/** The most an upload may be, all its files together (the server enforces it too). */
const val MAX_UPLOAD_BYTES = 200_000_000L
