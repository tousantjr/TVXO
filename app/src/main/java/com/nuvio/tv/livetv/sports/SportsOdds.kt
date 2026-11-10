package com.nuvio.tv.livetv.sports

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.abs
import kotlin.math.roundToInt

/** A betting line from ESPN's summary ("pickcenter" / "odds"): spread, total and money lines. */
data class BettingLine(
    val provider: String?,
    val details: String?,
    val spread: Double?,
    val overUnder: Double?,
    val awayMoneyLine: Int?,
    val homeMoneyLine: Int?,
    val homeFavorite: Boolean?
) {
    /** Win chances (percent) implied by the money lines, with the bookmaker's margin taken out. */
    val impliedHome: Double? get() = SportsOdds.noVig(homeMoneyLine, awayMoneyLine)?.first
    val impliedAway: Double? get() = SportsOdds.noVig(homeMoneyLine, awayMoneyLine)?.second
    val isEmpty: Boolean get() = details == null && spread == null && overUnder == null && awayMoneyLine == null && homeMoneyLine == null
}

/**
 * Pure parsing for the odds panel. Takes the raw JSON text of the summary's "pickcenter" (or
 * "odds") array so it can be unit tested without Android's org.json.
 */
internal object SportsOdds {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
    private fun JsonObject.str(name: String): String? =
        (this[name] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
    private fun JsonObject.dbl(name: String): Double? = (this[name] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.trim()?.toDoubleOrNull() }
    private fun JsonObject.bool(name: String): Boolean? = (this[name] as? JsonPrimitive)?.booleanOrNull

    /** The first usable line in [arrayJson] (ESPN lists one per provider), or null. */
    fun parse(arrayJson: String?): BettingLine? {
        if (arrayJson.isNullOrBlank()) return null
        val arr = runCatching { json.parseToJsonElement(arrayJson) as? JsonArray }.getOrNull() ?: return null
        return arr.asSequence().mapNotNull { it.obj() }.map { line(it) }.firstOrNull { !it.isEmpty }
    }

    private fun line(o: JsonObject): BettingLine {
        val home = o["homeTeamOdds"].obj()
        val away = o["awayTeamOdds"].obj()
        fun ml(t: JsonObject?): Int? = t?.dbl("moneyLine")?.roundToInt()
            ?: t?.get("current").obj()?.get("moneyLine").obj()?.str("american")?.removePrefix("+")?.toDoubleOrNull()?.roundToInt()
        return BettingLine(
            provider = o["provider"].obj()?.str("name"),
            details = o.str("details"),
            spread = o.dbl("spread"),
            overUnder = o.dbl("overUnder"),
            awayMoneyLine = ml(away),
            homeMoneyLine = ml(home),
            homeFavorite = home?.bool("favorite") ?: away?.bool("favorite")?.not()
        )
    }

    /** American odds -> raw implied chance (0–1). +150 -> 0.4, -200 -> 0.667. */
    fun implied(moneyLine: Int?): Double? = when {
        moneyLine == null || moneyLine == 0 || abs(moneyLine) < 100 -> null
        moneyLine > 0 -> 100.0 / (moneyLine + 100)
        else -> -moneyLine.toDouble() / (-moneyLine + 100)
    }

    /** Both teams' chances in percent, normalised to add up to 100. */
    fun noVig(homeMl: Int?, awayMl: Int?): Pair<Double, Double>? {
        val h = implied(homeMl) ?: return null
        val a = implied(awayMl) ?: return null
        val sum = h + a
        return (h / sum * 100) to (a / sum * 100)
    }

    /** "+150", "-170", or null. */
    fun formatMoneyLine(ml: Int?): String? = ml?.let { if (it > 0) "+$it" else "$it" }

    /** "-3.5" / "+3.5" / "PK" for a spread. */
    fun formatSpread(s: Double?): String? = when {
        s == null -> null
        s == 0.0 -> "PK"
        else -> (if (s > 0) "+" else "") + trim(s)
    }

    fun trim(d: Double): String = if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()
}
