package com.lumina.soltv.data

import com.lumina.soltv.model.Channel
import com.lumina.soltv.model.ContentType
import com.lumina.soltv.model.PlaylistData
import java.util.Locale
import java.util.UUID

object M3uParser {
    private val attr = Regex("([A-Za-z0-9_-]+)=[\\\"]([^\\\"]*)[\\\"]")

    fun parse(input: String): PlaylistData {
        val text = input.removePrefix("\uFEFF").trimStart()
        require(text.startsWith("#EXTM3U", true)) { "Playlist inválida: sem #EXTM3U" }
        val lines = text.lineSequence().map { it.trim().removeSuffix("\r") }.filter { it.isNotBlank() }
        var header: String? = null
        var pending: String? = null
        val out = ArrayList<Channel>(2048)
        for (line in lines) {
            if (header == null) { header = line; continue }
            when {
                line.startsWith("#EXTINF", true) -> pending = line
                line.startsWith("#") -> Unit
                pending != null && (line.startsWith("http://", true) || line.startsWith("https://", true) || line.startsWith("rtmp://", true) || line.startsWith("rtsp://", true)) -> {
                    val ext = pending!!
                    val attrs = attr.findAll(ext).associate { it.groupValues[1].lowercase(Locale.US) to it.groupValues[2] }
                    val name = ext.substringAfterLast(',', attrs["tvg-name"] ?: "Canal").trim().ifBlank { "Canal" }
                    val group = attrs["group-title"].orEmpty().ifBlank { "Outros" }
                    val url = line.replace("&amp;", "&")
                    out += Channel(
                        id = attrs["tvg-id"].orEmpty().ifBlank { UUID.nameUUIDFromBytes((name + url).toByteArray()).toString() },
                        name = name, logo = attrs["tvg-logo"], group = group, streamUrl = url,
                        tvgId = attrs["tvg-id"], tvgName = attrs["tvg-name"], type = classify(url, group)
                    )
                    pending = null
                }
            }
        }
        val epgUrl = Regex("(?:url-tvg|x-tvg-url)=[\\\"]([^\\\"]+)[\\\"]", RegexOption.IGNORE_CASE)
            .find(header.orEmpty())?.groupValues?.getOrNull(1)
        require(out.isNotEmpty()) { "Playlist M3U recebida, mas nenhum canal foi encontrado" }
        return PlaylistData(out, epgUrl)
    }

    private fun classify(url: String, group: String): ContentType {
        val s = (url + " " + group).lowercase(Locale.US)
        return when {
            listOf("series", "série", "seriados", "episode", "episódio").any { it in s } -> ContentType.SERIES
            listOf("movie", "filme", "vod", ".mp4", ".mkv", ".avi").any { it in s } -> ContentType.MOVIE
            else -> ContentType.LIVE
        }
    }
}
