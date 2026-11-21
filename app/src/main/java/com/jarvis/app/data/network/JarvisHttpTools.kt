package com.jarvis.app.data.network

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Xml
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.jarvis.app.domain.model.WeatherSnapshot
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.net.URI
import java.net.URLEncoder
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

data class IpGeoInfo(
    val lat: Double,
    val lon: Double,
    val place: String,
    val ip: String
)

data class GeoPoint(
    val name: String,
    val lat: Double,
    val lon: Double
)

class JarvisHttpTools {

    private val client = HttpClient(OkHttp) {
        engine {
            config {
                connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            }
        }
    }

    companion object {
        private const val USER_AGENT = "JarvisAndroid/1.0 (personal voice assistant)"

        fun wmoDescription(code: Int): String = when (code) {
            0 -> "Clear sky"
            1 -> "Mainly clear"
            2 -> "Partly cloudy"
            3 -> "Overcast"
            45, 48 -> "Foggy"
            51, 53, 55 -> "Drizzle"
            56, 57 -> "Freezing drizzle"
            61, 63, 65 -> "Rain"
            66, 67 -> "Freezing rain"
            71, 73, 75 -> "Snow"
            77 -> "Snow grains"
            80, 81, 82 -> "Rain showers"
            85, 86 -> "Snow showers"
            95 -> "Thunderstorm"
            96, 99 -> "Thunderstorm with hail"
            else -> "Variable"
        }

        fun isPrivateHost(host: String): Boolean {
            val h = host.lowercase().removeSurrounding("[", "]")
            if (h == "localhost" || h == "::1") return true
            if (Regex("\\.(local|localhost|internal|lan|home)$").containsMatchIn(h)) return true
            if (Regex("^(127\\.|10\\.|0\\.|169\\.254\\.|192\\.168\\.|172\\.(1[6-9]|2\\d|3[01])\\.)").containsMatchIn(h)) return true
            if (Regex("^(fc|fd|fe80)[0-9a-f]*:").containsMatchIn(h)) return true
            return false
        }

        fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6_371_000.0
            val p1 = Math.toRadians(lat1)
            val p2 = Math.toRadians(lat2)
            val dp = Math.toRadians(lat2 - lat1)
            val dl = Math.toRadians(lon2 - lon1)
            val a = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
            return 2 * r * asin(sqrt(a))
        }

        fun prettyDist(meters: Double): String =
            if (meters < 950) "${(meters / 10).roundToInt() * 10} m"
            else String.format(java.util.Locale.US, "%.1f km", meters / 1000.0)
    }

    suspend fun ipGeoLookup(): IpGeoInfo? = withContext(Dispatchers.IO) {
        try {
            val resp = client.get("https://ipwho.is/?fields=success,ip,latitude,longitude,city,region") {
                header("User-Agent", USER_AGENT)
            }
            if (resp.status.isSuccess()) {
                val json = JSONObject(resp.bodyAsText())
                if (json.optBoolean("success", true) && json.has("latitude") && json.has("longitude")) {
                    val place = listOf(json.optString("city"), json.optString("region"))
                        .filter { it.isNotBlank() }
                        .joinToString(", ")
                    return@withContext IpGeoInfo(
                        lat = json.getDouble("latitude"),
                        lon = json.getDouble("longitude"),
                        place = place,
                        ip = json.optString("ip", "—")
                    )
                }
            }
        } catch (_: Exception) {
        }
        try {
            val resp = client.get("https://ipapi.co/json/") {
                header("User-Agent", USER_AGENT)
            }
            if (resp.status.isSuccess()) {
                val json = JSONObject(resp.bodyAsText())
                if (!json.optBoolean("error", false) && json.has("latitude") && json.has("longitude")) {
                    val place = listOf(json.optString("city"), json.optString("region"))
                        .filter { it.isNotBlank() }
                        .joinToString(", ")
                    return@withContext IpGeoInfo(
                        lat = json.getDouble("latitude"),
                        lon = json.getDouble("longitude"),
                        place = place,
                        ip = json.optString("ip", "—")
                    )
                }
            }
        } catch (_: Exception) {
        }
        null
    }

    suspend fun geocode(query: String, nearLat: Double? = null, nearLon: Double? = null): GeoPoint? =
        withContext(Dispatchers.IO) {
            val cleaned = query
                .replace(Regex("^(the\\s+)?(nearest|closest|nearby|local|a|an|some|any|my\\s+local)\\s+", RegexOption.IGNORE_CASE), "")
                .trim()
                .trimEnd('?', '.')
            if (cleaned.isEmpty()) return@withContext null

            suspend fun queryNominatim(bounded: Boolean): List<GeoPoint> {
                return try {
                    val resp = client.get("https://nominatim.openstreetmap.org/search") {
                        header("User-Agent", USER_AGENT)
                        parameter("q", cleaned)
                        parameter("format", "json")
                        parameter("limit", "5")
                        if (nearLat != null && nearLon != null) {
                            val d = 0.6
                            parameter("viewbox", "${nearLon - d},${nearLat + d},${nearLon + d},${nearLat - d}")
                            if (bounded) parameter("bounded", "1")
                        }
                    }
                    if (!resp.status.isSuccess()) return emptyList()
                    val arr = JSONArray(resp.bodyAsText())
                    (0 until arr.length()).mapNotNull { i ->
                        val row = arr.optJSONObject(i) ?: return@mapNotNull null
                        val rLat = row.optString("lat").toDoubleOrNull() ?: return@mapNotNull null
                        val rLon = row.optString("lon").toDoubleOrNull() ?: return@mapNotNull null
                        GeoPoint(row.optString("display_name", cleaned), rLat, rLon)
                    }
                } catch (_: Exception) {
                    emptyList()
                }
            }

            var rows = if (nearLat != null && nearLon != null) queryNominatim(true) else queryNominatim(false)
            if (rows.isEmpty() && nearLat != null && nearLon != null) {
                rows = queryNominatim(false)
            }
            if (rows.isEmpty()) return@withContext null
            if (nearLat != null && nearLon != null) {
                rows.minByOrNull { haversineMeters(nearLat, nearLon, it.lat, it.lon) }
            } else {
                rows.first()
            }
        }

    suspend fun fetchWeather(lat: Double, lon: Double, placeLabel: String): WeatherSnapshot =
        withContext(Dispatchers.IO) {
            try {
                val resp = client.get("https://api.open-meteo.com/v1/forecast") {
                    header("User-Agent", USER_AGENT)
                    parameter("latitude", lat)
                    parameter("longitude", lon)
                    parameter("current", "temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m,apparent_temperature")
                    parameter("hourly", "temperature_2m,weather_code")
                    parameter("daily", "weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max")
                    parameter("forecast_days", "3")
                    parameter("timezone", "auto")
                }
                if (!resp.status.isSuccess()) {
                    return@withContext WeatherSnapshot(place = placeLabel, error = "HTTP ${resp.status.value}")
                }
                val json = JSONObject(resp.bodyAsText())
                val cur = json.optJSONObject("current")
                val temp = cur?.optDouble("temperature_2m")?.roundToInt()
                val feels = cur?.optDouble("apparent_temperature")?.roundToInt()
                val humidity = cur?.optInt("relative_humidity_2m")
                val wind = cur?.optDouble("wind_speed_10m")?.roundToInt()
                val code = cur?.optInt("weather_code", -1) ?: -1
                val cond = wmoDescription(code)

                val hourlyObj = json.optJSONObject("hourly")
                val hTimes = hourlyObj?.optJSONArray("time")
                val hTemps = hourlyObj?.optJSONArray("temperature_2m")
                val hCodes = hourlyObj?.optJSONArray("weather_code")
                val hourly = mutableListOf<Pair<String, String>>()
                if (hTimes != null && hTemps != null) {
                    for (i in 0 until minOf(8, hTimes.length())) {
                        val tStr = hTimes.optString(i).substringAfter("T", "").take(5)
                        val tVal = hTemps.optDouble(i).roundToInt()
                        val wStr = if (hCodes != null) wmoDescription(hCodes.optInt(i, -1)) else ""
                        if (tStr.isNotBlank()) hourly.add(tStr to "${tVal}°C $wStr".trim())
                    }
                }

                val dailyObj = json.optJSONObject("daily")
                val dTimes = dailyObj?.optJSONArray("time")
                val dMax = dailyObj?.optJSONArray("temperature_2m_max")
                val dMin = dailyObj?.optJSONArray("temperature_2m_min")
                val dCode = dailyObj?.optJSONArray("weather_code")
                val dPop = dailyObj?.optJSONArray("precipitation_probability_max")
                val days = mutableListOf<String>()
                if (dTimes != null) {
                    val labels = listOf("Today", "Tomorrow", "Day 3")
                    for (i in 0 until minOf(3, dTimes.length())) {
                        val hi = dMax?.optDouble(i)?.roundToInt() ?: 0
                        val lo = dMin?.optDouble(i)?.roundToInt() ?: 0
                        val dc = wmoDescription(dCode?.optInt(i, -1) ?: -1)
                        val pop = dPop?.optInt(i, -1) ?: -1
                        val rainStr = if (pop >= 0) " · ${pop}% rain" else ""
                        days.add("${labels.getOrElse(i) { dTimes.optString(i) }}: ${lo}–${hi}°C, $dc$rainStr")
                    }
                }

                WeatherSnapshot(
                    place = placeLabel,
                    temperatureC = temp,
                    feelsLikeC = feels,
                    condition = cond,
                    humidityPercent = humidity,
                    windKmh = wind,
                    hourlySummary = hourly,
                    forecastDays = days,
                    updatedAtMs = System.currentTimeMillis()
                )
            } catch (e: Exception) {
                WeatherSnapshot(place = placeLabel, error = e.localizedMessage ?: "Weather unavailable")
            }
        }

    suspend fun fetchNews(topic: String = ""): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val sections = mapOf(
            "world" to "WORLD",
            "business" to "BUSINESS",
            "tech" to "TECHNOLOGY",
            "technology" to "TECHNOLOGY",
            "science" to "SCIENCE",
            "sport" to "SPORTS",
            "sports" to "SPORTS",
            "health" to "HEALTH",
            "entertainment" to "ENTERTAINMENT"
        )
        val clean = topic.trim().lowercase()
        val base = "https://news.google.com/rss"
        val url = when {
            clean.isEmpty() || clean in setOf("news", "top", "headlines", "latest") -> "$base?hl=en&gl=US&ceid=US:en"
            clean in sections -> "$base/headlines/section/topic/${sections[clean]}?hl=en&gl=US&ceid=US:en"
            else -> "$base/search?q=${URLEncoder.encode(topic.trim(), "UTF-8")}&hl=en&gl=US&ceid=US:en"
        }
        return@withContext try {
            val resp = client.get(url) { header("User-Agent", USER_AGENT) }
            if (!resp.status.isSuccess()) return@withContext false to "Couldn't fetch headlines (HTTP ${resp.status.value})."
            val xml = resp.bodyAsText()
            val parser = Xml.newPullParser()
            parser.setInput(StringReader(xml))
            val titles = mutableListOf<String>()
            var inItem = false
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT && titles.size < 5) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        if (parser.name == "item") inItem = true
                        else if (inItem && parser.name == "title") {
                            val raw = parser.nextText().trim()
                            val cut = raw.lastIndexOf(" - ")
                            val title = if (cut > 0) raw.substring(0, cut).trim() else raw
                            if (title.isNotEmpty()) titles.add(title)
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "item") inItem = false
                    }
                }
                event = parser.next()
            }
            if (titles.isEmpty()) true to "No headlines found right now."
            else true to "Top headlines:\n" + titles.mapIndexed { idx, t -> "${idx + 1}. $t" }.joinToString("\n")
        } catch (e: Exception) {
            false to "Couldn't fetch the news right now (${e.localizedMessage ?: "network error"})."
        }
    }

    suspend fun findNearbyPlaces(query: String, lat: Double, lon: Double): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            val clean = query.trim().ifBlank { "cafe" }
            val overpassQuery = """
                [out:json][timeout:10];
                (
                  node["name"~"${clean.replace("\"", "")}",i](around:3500,$lat,$lon);
                  node["amenity"~"${clean.replace("\"", "")}",i](around:3500,$lat,$lon);
                  node["shop"~"${clean.replace("\"", "")}",i](around:3500,$lat,$lon);
                );
                out body 8;
            """.trimIndent()
            try {
                val resp = client.post("https://overpass-api.de/api/interpreter") {
                    header("User-Agent", USER_AGENT)
                    contentType(ContentType.Text.Plain)
                    setBody(overpassQuery)
                }
                if (resp.status.isSuccess()) {
                    val elements = JSONObject(resp.bodyAsText()).optJSONArray("elements")
                    val lines = mutableListOf<Pair<Double, String>>()
                    if (elements != null) {
                        for (i in 0 until elements.length()) {
                            val el = elements.optJSONObject(i) ?: continue
                            val tags = el.optJSONObject("tags")
                            val name = tags?.optString("name")?.takeIf { it.isNotBlank() } ?: continue
                            val eLat = el.optDouble("lat", Double.NaN)
                            val eLon = el.optDouble("lon", Double.NaN)
                            if (eLat.isNaN() || eLon.isNaN()) continue
                            val dist = haversineMeters(lat, lon, eLat, eLon)
                            val addr = tags.optString("addr:street").takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
                            lines.add(dist to "$name$addr — ${prettyDist(dist)}")
                        }
                    }
                    if (lines.isNotEmpty()) {
                        val sorted = lines.sortedBy { it.first }.take(5).mapIndexed { i, p -> "${i + 1}. ${p.second}" }
                        return@withContext true to "Nearby matches for \"$clean\":\n" + sorted.joinToString("\n")
                    }
                }
            } catch (_: Exception) {
            }
            val geo = geocode(clean, lat, lon)
            if (geo != null) {
                val dist = prettyDist(haversineMeters(lat, lon, geo.lat, geo.lon))
                true to "Closest match: ${geo.name} ($dist away)."
            } else {
                false to "Couldn't find nearby places matching \"$clean\"."
            }
        }

    suspend fun getDirections(originLat: Double, originLon: Double, destination: String): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            val dest = geocode(destination, originLat, originLon)
                ?: return@withContext false to "Couldn't locate \"$destination\"."
            return@withContext try {
                val url = "https://router.project-osrm.org/route/v1/driving/$originLon,$originLat;${dest.lon},${dest.lat}?overview=false&steps=true"
                val resp = client.get(url) { header("User-Agent", USER_AGENT) }
                if (!resp.status.isSuccess()) {
                    val dist = prettyDist(haversineMeters(originLat, originLon, dest.lat, dest.lon))
                    return@withContext true to "${dest.name} is about $dist away."
                }
                val route = JSONObject(resp.bodyAsText()).optJSONArray("routes")?.optJSONObject(0)
                    ?: return@withContext false to "No driving route found to ${dest.name}."
                val distM = route.optDouble("distance", 0.0)
                val durMin = (route.optDouble("duration", 0.0) / 60.0).roundToInt().coerceAtLeast(1)
                true to "Route to ${dest.name}: ${prettyDist(distM)}, about $durMin min driving."
            } catch (e: Exception) {
                false to "Couldn't compute directions (${e.localizedMessage ?: "network error"})."
            }
        }

    suspend fun readWebpage(url: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val parsed = try { URI(url.trim()) } catch (_: Exception) { return@withContext false to "Invalid URL." }
        val host = parsed.host ?: return@withContext false to "URL has no host."
        if (parsed.scheme !in setOf("http", "https")) return@withContext false to "Only http/https URLs are supported."
        if (isPrivateHost(host)) return@withContext false to "Refused to fetch private/local network address ($host)."
        return@withContext try {
            val resp = client.get(url.trim()) { header("User-Agent", USER_AGENT) }
            if (!resp.status.isSuccess()) return@withContext false to "HTTP ${resp.status.value} reading $host."
            val html = resp.bodyAsText()
            val noScripts = html
                .replace(Regex("<!--[\\s\\S]*?-->"), "")
                .replace(Regex("<(script|style|noscript|svg|nav|footer|header|aside)[\\s\\S]*?</\\1>", RegexOption.IGNORE_CASE), " ")
            val title = Regex("<title[^>]*>([\\s\\S]*?)</title>", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.getOrNull(1)?.trim().orEmpty()
            val text = noScripts
                .replace(Regex("<[^>]+>"), " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&nbsp;", " ")
                .replace(Regex("\\s+"), " ")
                .trim()
                .take(4500)
            if (text.isBlank()) true to "Fetched $host, but the page relies on client scripts and has little static text."
            else true to (if (title.isNotBlank()) "Page: $title\n" else "") + text
        } catch (e: Exception) {
            false to "Couldn't read $host: ${e.localizedMessage ?: "network error"}."
        }
    }

    suspend fun tavilySearch(query: String, apiKey: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject()
                .put("api_key", apiKey)
                .put("query", query)
                .put("search_depth", "basic")
                .put("max_results", 5)
            val resp = client.post("https://api.tavily.com/search") {
                contentType(ContentType.Application.Json)
                setBody(payload.toString())
            }
            if (!resp.status.isSuccess()) return@withContext false to "Tavily HTTP ${resp.status.value}"
            val results = JSONObject(resp.bodyAsText()).optJSONArray("results") ?: JSONArray()
            val lines = (0 until results.length()).mapNotNull { i ->
                val item = results.optJSONObject(i) ?: return@mapNotNull null
                val t = item.optString("title")
                val c = item.optString("content").take(240)
                val u = item.optString("url")
                "${i + 1}. $t ($u): $c"
            }
            if (lines.isEmpty()) false to "No search results."
            else true to lines.joinToString("\n")
        } catch (e: Exception) {
            false to (e.localizedMessage ?: "Search error")
        }
    }

    fun generateQrBitmap(text: String, sizePx: Int = 512): Bitmap? {
        if (text.isBlank()) return null
        return try {
            val hints = mapOf(EncodeHintType.MARGIN to 2)
            val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
            val pixels = IntArray(sizePx * sizePx)
            for (y in 0 until sizePx) {
                val offset = y * sizePx
                for (x in 0 until sizePx) {
                    pixels[offset + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
                }
            }
            Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888).apply {
                setPixels(pixels, 0, sizePx, 0, 0, sizePx, sizePx)
            }
        } catch (_: Exception) {
            null
        }
    }
}
