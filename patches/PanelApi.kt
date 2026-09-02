package com.lumina.soltv.api

import com.lumina.soltv.model.AuthState
import com.lumina.soltv.model.Portal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class PanelApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    suspend fun authenticate(mac: String): AuthState = withContext(Dispatchers.IO) {
        val inner = JSONObject().put("app_device_id", mac).put("mac_address", mac).toString()
        val outer = JSONObject().put("data", MopeCodec.encrypt(inner)).toString()
        val req = Request.Builder().url(ServerConfig.AUTH_URL)
            .header("Accept", "application/json")
            .header("User-Agent", "LuminaTV/1.2 Android")
            .post(outer.toRequestBody(jsonType)).build()
        client.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty().trim()
            if (!resp.isSuccessful) error("AUTH HTTP ${resp.code}: ${raw.take(160)}")
            val root = JSONObject(raw)
            val plain = root.optJSONObject("plain")
                ?: root.optJSONObject("data")
                ?: root.optString("data").takeIf { it.isNotBlank() }?.let(MopeCodec::decrypt)?.let(::JSONObject)
                ?: root

            val arrays = listOf("urls", "playlists", "playlist", "portals")
                .mapNotNull { plain.optJSONArray(it) }
            val portals = buildList {
                arrays.forEach { arr ->
                    for (i in 0 until arr.length()) {
                        val p = arr.optJSONObject(i) ?: continue
                        val url = sequenceOf("url", "playlist_url", "m3u_url", "link", "dns")
                            .map { p.optString(it) }.firstOrNull { it.isNotBlank() }.orEmpty()
                        if (url.isNotBlank()) add(Portal(
                            id = p.optString("id", i.toString()),
                            name = p.optString("name", "Playlist ${i + 1}"),
                            url = normalizeUrl(url),
                            type = p.optString("type", "m3u"),
                            isProtected = p.optString("is_protected", "0")
                        ))
                    }
                }
                if (isEmpty()) {
                    val url = sequenceOf("url", "playlist_url", "m3u_url", "link")
                        .map { plain.optString(it) }.firstOrNull { it.isNotBlank() }.orEmpty()
                    if (url.isNotBlank()) add(Portal("0", "Playlist", normalizeUrl(url), "m3u"))
                }
            }.distinctBy { it.url }

            AuthState(
                deviceKey = plain.optString("device_key", plain.optString("key")),
                macAddress = plain.optString("mac_address", mac),
                apkUrl = plain.optString("apk_url", plain.optString("apk_link")),
                appVersion = plain.optString("app_version", plain.optString("android_version_code")),
                parentalPin = plain.optString("parent_control", "0000"),
                portals = portals
            )
        }
    }

    private fun normalizeUrl(value: String): String = value.trim().replace("&amp;", "&")

    suspend fun updatePin(mac: String, pin: String): Boolean = withContext(Dispatchers.IO) {
        val inner = JSONObject().put("mac_address", mac).put("parent_control", pin).toString()
        val outer = JSONObject().put("data", MopeCodec.encrypt(inner)).toString()
        val req = Request.Builder().url(ServerConfig.PIN_UPDATE_URL).post(outer.toRequestBody(jsonType)).build()
        client.newCall(req).execute().use { resp ->
            resp.isSuccessful && runCatching { JSONObject(resp.body?.string().orEmpty()).optBoolean("status", false) }.getOrDefault(false)
        }
    }

    suspend fun downloadText(url: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(normalizeUrl(url))
            .header("Accept", "*/*")
            .header("User-Agent", "LuminaTV/1.2 Android")
            .get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("Playlist HTTP ${resp.code}")
            val body = resp.body?.string().orEmpty().removePrefix("\uFEFF").trimStart()
            if (!body.startsWith("#EXTM3U", true)) error("Resposta da lista não é M3U: ${body.take(120)}")
            body
        }
    }
}
