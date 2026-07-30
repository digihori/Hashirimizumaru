package tk.horiuchi.hashirimizumaru

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

sealed interface ContourState {
    data object Idle : ContourState
    data object Loading : ContourState
    data class Ready(val geoJson: String, val fromCache: Boolean) : ContourState
    data class Error(val message: String) : ContourState
}

class ContourRepository(context: Context) {
    private val cacheFile = File(context.filesDir, "depth_contours_20_50_100m.geojson")

    suspend fun cached(): ContourState.Ready? = withContext(Dispatchers.IO) {
        cacheFile.takeIf { it.isFile && it.length() > 0 }
            ?.readText()
            ?.let { ContourState.Ready(it, fromCache = true) }
    }

    suspend fun download(): ContourState.Ready = withContext(Dispatchers.IO) {
        check(BuildConfig.MSIL_SUBSCRIPTION_KEY.isNotBlank()) {
            "local.properties に MSIL_SUBSCRIPTION_KEY が設定されていません"
        }
        val selected = JSONArray()
        listOf(10, 11, 12).forEach { layer ->
            val response = requestLayer(layer)
            appendTargetFeatures(response, selected)
        }
        check(selected.length() > 0) {
            "対象範囲（大津港〜走水）の等深線が見つかりませんでした"
        }
        val filtered = JSONObject()
            .put("type", "FeatureCollection")
            .put("features", selected)
            .toString()
        cacheFile.writeText(filtered)
        ContourState.Ready(filtered, fromCache = false)
    }

    private fun requestLayer(layer: Int): String {
        val url = URL(
            "https://api.msil.go.jp/depth-contour/v2/MapServer/$layer/query" +
                "?f=geojson&where=1%3D1&returnGeometry=true"
        )
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/geo+json, application/json")
            setRequestProperty("Ocp-Apim-Subscription-Key", BuildConfig.MSIL_SUBSCRIPTION_KEY)
        }
        try {
            val status = connection.responseCode
            if (status !in 200..299) {
                val detail = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                error("海しるAPI HTTP $status${detail.takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty()}")
            }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun appendTargetFeatures(source: String, selected: JSONArray) {
        val root = JSONObject(source)
        val sourceFeatures = root.optJSONArray("features") ?: JSONArray()
        for (index in 0 until sourceFeatures.length()) {
            val feature = sourceFeatures.optJSONObject(index) ?: continue
            val coordinates = feature.optJSONObject("geometry")
                ?.optJSONArray("coordinates") ?: continue
            if (containsTargetCoordinate(coordinates)) selected.put(feature)
        }
    }

    private fun containsTargetCoordinate(value: JSONArray): Boolean {
        if (value.length() >= 2 && value.opt(0) is Number && value.opt(1) is Number) {
            val longitude = value.optDouble(0)
            val latitude = value.optDouble(1)
            return longitude in 139.650..139.820 && latitude in 35.235..35.340
        }
        for (index in 0 until value.length()) {
            val child = value.optJSONArray(index) ?: continue
            if (containsTargetCoordinate(child)) return true
        }
        return false
    }
}
