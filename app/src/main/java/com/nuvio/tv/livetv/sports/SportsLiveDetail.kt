package com.nuvio.tv.livetv.sports

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** One line of play-by-play. */
data class LivePlay(
    val text: String,
    val period: Int?,
    val clock: String?,
    val scoring: Boolean,
    val teamId: String?,
    val awayScore: Int?,
    val homeScore: Int?
)

/** A box score for one team: the main stat group with its best players. */
data class BoxTeam(val teamId: String?, val team: String, val group: String?, val labels: List<String>, val rows: List<BoxRow>)
data class BoxRow(val name: String, val stats: List<String>)

/**
 * Pure parsing of ESPN summary parts for the live game page (play-by-play and box score).
 * Takes raw JSON text so it is testable without Android's org.json.
 */
internal object SportsLiveDetail {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    const val MAX_PLAYS = 12
    const val MAX_ROWS = 5
    const val MAX_STATS = 5

    private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
    private fun JsonElement?.arr(): List<JsonObject> = (this as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
    private fun JsonObject.str(name: String): String? =
        (this[name] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
    private fun JsonObject.int(name: String): Int? = (this[name] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }

    private fun parse(text: String?): JsonElement? =
        if (text.isNullOrBlank()) null else runCatching { json.parseToJsonElement(text) }.getOrNull()

    /**
     * Latest plays, newest first. [playsJson]: the summary's "plays" array; when that is missing,
     * [commentaryJson] (football's "commentary" or "keyEvents") is used instead.
     */
    fun plays(playsJson: String?, commentaryJson: String? = null, max: Int = MAX_PLAYS): List<LivePlay> {
        val fromPlays = parse(playsJson).arr().mapNotNull { p ->
            val text = p.str("text") ?: p["type"].obj()?.str("text") ?: return@mapNotNull null
            LivePlay(
                text = text,
                period = p["period"].obj()?.int("number"),
                clock = p["clock"].obj()?.str("displayValue"),
                scoring = (p["scoringPlay"] as? JsonPrimitive)?.booleanOrNull == true,
                teamId = p["team"].obj()?.str("id"),
                awayScore = p.int("awayScore"),
                homeScore = p.int("homeScore")
            )
        }
        if (fromPlays.isNotEmpty()) return fromPlays.asReversed().take(max)
        val fromCommentary = parse(commentaryJson).arr().mapNotNull { c ->
            val text = c.str("text") ?: c["play"].obj()?.str("text") ?: return@mapNotNull null
            LivePlay(
                text = text,
                period = c["period"].obj()?.int("number"),
                clock = c["time"].obj()?.str("displayValue") ?: c["clock"].obj()?.str("displayValue"),
                scoring = (c["scoringPlay"] as? JsonPrimitive)?.booleanOrNull == true ||
                    c["type"].obj()?.str("type")?.contains("goal", ignoreCase = true) == true,
                teamId = c["team"].obj()?.str("id") ?: c["play"].obj()?.get("team").obj()?.str("id"),
                awayScore = null,
                homeScore = null
            )
        }
        return fromCommentary.asReversed().take(max)
    }

    /** "boxscore.players": the first stat group per team, top [MAX_ROWS] players. */
    fun boxscore(playersJson: String?): List<BoxTeam> = parse(playersJson).arr().mapNotNull { t ->
        val team = t["team"].obj()
        val group = t["statistics"].arr().firstOrNull { it["athletes"].arr().isNotEmpty() } ?: return@mapNotNull null
        val labels = ((group["labels"] ?: group["names"]) as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.take(MAX_STATS) ?: emptyList()
        val rows = group["athletes"].arr().mapNotNull { a ->
            val name = a["athlete"].obj()?.let { it.str("shortName") ?: it.str("displayName") } ?: return@mapNotNull null
            val stats = (a["stats"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.take(MAX_STATS) ?: emptyList()
            if (stats.isEmpty()) null else BoxRow(name, stats)
        }.take(MAX_ROWS)
        if (rows.isEmpty()) null
        else BoxTeam(team?.str("id"), team?.str("abbreviation") ?: team?.str("displayName") ?: "", group.str("name") ?: group.str("text"), labels, rows)
    }
}
