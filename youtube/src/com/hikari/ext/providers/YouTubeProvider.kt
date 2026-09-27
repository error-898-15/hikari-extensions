package com.hikari.ext.providers

import com.hikari.ext.HikariCatalog
import com.hikari.ext.HikariEpisode
import com.hikari.ext.HikariMedia
import com.hikari.ext.HikariMediaType
import com.hikari.ext.HikariNet
import com.hikari.ext.HikariProvider
import com.hikari.ext.HikariStream
import com.hikari.ext.HikariSubtitle
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * YouTube Provider for Hikari.
 *
 * Supports:
 * - Search via official Innertube Web JSON API with smart HTML fallback
 * - Catalogs: Trending, Trailers, Movies, Music, Documentaries, Animation, Web Shows
 * - Metadata via oEmbed and Innertube
 * - Playlists & single video playback
 * - Multi-Server options:
 *   1. YouTube Native Player (direct Hikari internal ytId streaming)
 *   2. YouTube Web Embed (responsive privacy-enhanced player)
 *   3. YouTube Mobile Web (official mobile web destination)
 *   4. YouTube Desktop Web (browser destination)
 */
class YouTubeProvider : HikariProvider {
    override val id = "youtube"
    override val name = "YouTube"
    override val mainUrl = "https://www.youtube.com"
    override val version = 1
    override val description = "YouTube videos, trailers, movies, music, and documentaries with multi-server playback."
    override val iconUrl: String? = "https://www.youtube.com/s/desktop/af0a3c1e/img/favicon_144x144.png"
    override val tvTypes = setOf(HikariMediaType.MOVIE, HikariMediaType.SERIES)

    companion object {
        private const val BASE = "https://www.youtube.com"
        private const val INNERTUBE_SEARCH = "https://www.youtube.com/youtubei/v1/search"
        private const val INNERTUBE_BROWSE = "https://www.youtube.com/youtubei/v1/browse"
        private const val OEMBED_URL = "https://www.youtube.com/oembed"
        private val YEAR_REGEX = Regex("""\b(19\d\d|20\d\d)\b""")
    }

    override fun catalogs(): List<HikariCatalog> = listOf(
        HikariCatalog("trending", "Trending Now", HikariMediaType.MOVIE),
        HikariCatalog("trailers", "Trailers & Teasers", HikariMediaType.MOVIE),
        HikariCatalog("movies", "Movies & Films", HikariMediaType.MOVIE),
        HikariCatalog("music", "Music Videos", HikariMediaType.MOVIE),
        HikariCatalog("documentaries", "Documentaries", HikariMediaType.MOVIE),
        HikariCatalog("animation", "Animation & Anime", HikariMediaType.MOVIE),
        HikariCatalog("shows", "Web Series & Shows", HikariMediaType.SERIES),
    )

    override suspend fun getCatalog(catalog: HikariCatalog, page: Int): List<HikariMedia> {
        val query = when (catalog.id) {
            "trending" -> "trending popular"
            "trailers" -> "official movie trailers"
            "movies" -> "full movies english"
            "music" -> "top music video official"
            "documentaries" -> "full documentary"
            "animation" -> "anime official animation short"
            "shows" -> "web series full episodes"
            else -> catalog.name
        }
        return search(query, page)
    }

    override suspend fun search(query: String, page: Int): List<HikariMedia> {
        if (query.isBlank()) return emptyList()

        // 1. Try official YouTube Innertube Web JSON API
        val results = searchInnertube(query)
        if (results.isNotEmpty()) {
            return results
        }

        // 2. Fallback to HTML scraping via getStringSmart if Innertube is blocked
        return searchHtml(query)
    }

    private suspend fun searchInnertube(query: String): List<HikariMedia> {
        val body = JSONObject().apply {
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", "WEB")
                    put("clientVersion", "2.20231121.00.00")
                    put("hl", "en")
                    put("gl", "US")
                })
            })
            put("query", query)
        }

        val json = HikariNet.postJson(
            INNERTUBE_SEARCH,
            body,
            mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        ) ?: return emptyList()

        val list = mutableListOf<HikariMedia>()
        val primaryContents = json.optJSONObject("contents")
            ?.optJSONObject("twoColumnSearchResultsRenderer")
            ?.optJSONObject("primaryContents")
            ?.optJSONObject("sectionListRenderer")
            ?.optJSONArray("contents") ?: return emptyList()

        for (i in 0 until primaryContents.length()) {
            val section = primaryContents.optJSONObject(i) ?: continue
            val items = section.optJSONObject("itemSectionRenderer")?.optJSONArray("contents") ?: continue

            for (j in 0 until items.length()) {
                val item = items.optJSONObject(j) ?: continue

                // Check video item
                val video = item.optJSONObject("videoRenderer")
                if (video != null) {
                    val vid = video.optString("videoId")
                    if (vid.isNullOrBlank()) continue

                    val title = parseRuns(video.optJSONObject("title"))
                    val desc = parseSnippets(video.optJSONArray("detailedMetadataSnippets"))
                    val channel = parseRuns(video.optJSONObject("ownerText"))
                    val poster = "https://i.ytimg.com/vi/$vid/hqdefault.jpg"
                    val year = YEAR_REGEX.find("$title $desc")?.value?.toIntOrNull()

                    list += HikariMedia(
                        id = vid,
                        title = if (title.isNotBlank()) title else "YouTube Video $vid",
                        type = HikariMediaType.MOVIE,
                        posterUrl = poster,
                        year = year,
                        overview = if (desc.isNotBlank()) desc else if (channel.isNotBlank()) "By $channel" else null,
                        genres = if (channel.isNotBlank()) listOf(channel) else emptyList(),
                    )
                }

                // Check playlist / show item
                val playlist = item.optJSONObject("playlistRenderer")
                if (playlist != null) {
                    val pid = playlist.optString("playlistId")
                    if (pid.isNullOrBlank()) continue

                    val title = parseRuns(playlist.optJSONObject("title"))
                    val channel = parseRuns(playlist.optJSONObject("shortBylineText"))
                    val thumbnails = playlist.optJSONObject("thumbnails")?.optJSONArray("thumbnails")
                        ?: playlist.optJSONArray("thumbnails")
                    val poster = thumbnails?.optJSONObject(0)?.optString("url")

                    list += HikariMedia(
                        id = "playlist:$pid",
                        title = if (title.isNotBlank()) title else "YouTube Playlist",
                        type = HikariMediaType.SERIES,
                        posterUrl = poster,
                        overview = if (channel.isNotBlank()) "Playlist by $channel" else null,
                        genres = if (channel.isNotBlank()) listOf(channel) else emptyList(),
                    )
                }
            }
        }
        return list
    }

    private suspend fun searchHtml(query: String): List<HikariMedia> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val html = HikariNet.getStringSmart("$BASE/results?search_query=$encoded") ?: return emptyList()

        val list = mutableListOf<HikariMedia>()
        val videoPattern = Regex(""""videoId":"([a-zA-Z0-9_-]{11})".*?"title":\{"runs":\[\{"text":"([^"]+)"""")
        val matches = videoPattern.findAll(html)

        val seen = mutableSetOf<String>()
        for (match in matches) {
            val vid = match.groupValues[1]
            if (vid in seen) continue
            seen.add(vid)

            val rawTitle = match.groupValues[2]
            val title = unescape(rawTitle)
            val poster = "https://i.ytimg.com/vi/$vid/hqdefault.jpg"
            val year = YEAR_REGEX.find(title)?.value?.toIntOrNull()

            list += HikariMedia(
                id = vid,
                title = title,
                type = HikariMediaType.MOVIE,
                posterUrl = poster,
                year = year,
            )
        }
        return list
    }

    override suspend fun getMeta(media: HikariMedia): HikariMedia {
        if (media.id.startsWith("playlist:")) return media

        val videoId = media.id.trim()
        val oembedUrl = "$OEMBED_URL?url=https://www.youtube.com/watch?v=$videoId&format=json"
        val json = HikariNet.getJson(oembedUrl) ?: return media

        val title = json.optString("title").ifBlank { media.title }
        val author = json.optString("author_name")
        val poster = json.optString("thumbnail_url").ifBlank {
            media.posterUrl ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
        }
        val overview = if (media.overview.isNullOrBlank()) {
            if (author.isNotBlank()) "Published by $author on YouTube" else null
        } else {
            media.overview
        }

        return media.copy(
            title = title,
            posterUrl = poster,
            overview = overview,
            genres = if (author.isNotBlank() && media.genres.isEmpty()) listOf(author) else media.genres,
        )
    }

    override suspend fun getEpisodes(media: HikariMedia): List<HikariEpisode>? {
        if (media.type == HikariMediaType.SERIES || media.id.startsWith("playlist:")) {
            val playlistId = media.id.removePrefix("playlist:")
            val eps = fetchPlaylistEpisodes(playlistId)
            if (eps.isNotEmpty()) return eps
        }
        return listOf(
            HikariEpisode(
                number = 1,
                id = media.id,
                name = media.title,
                image = media.posterUrl,
                season = 1,
            )
        )
    }

    private suspend fun fetchPlaylistEpisodes(playlistId: String): List<HikariEpisode> {
        val body = JSONObject().apply {
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", "WEB")
                    put("clientVersion", "2.20231121.00.00")
                })
            })
            put("browseId", "VL$playlistId")
        }

        val json = HikariNet.postJson(INNERTUBE_BROWSE, body) ?: return emptyList()
        val list = mutableListOf<HikariEpisode>()

        val tabs = json.optJSONObject("contents")
            ?.optJSONObject("twoColumnBrowseResultsRenderer")
            ?.optJSONArray("tabs") ?: return emptyList()

        for (t in 0 until tabs.length()) {
            val tab = tabs.optJSONObject(t) ?: continue
            val contents = tab.optJSONObject("tabRenderer")
                ?.optJSONObject("content")
                ?.optJSONObject("sectionListRenderer")
                ?.optJSONArray("contents") ?: continue

            for (c in 0 until contents.length()) {
                val sec = contents.optJSONObject(c) ?: continue
                val items = sec.optJSONObject("itemSectionRenderer")
                    ?.optJSONArray("contents")
                    ?.optJSONObject(0)
                    ?.optJSONObject("playlistVideoListRenderer")
                    ?.optJSONArray("contents") ?: continue

                for (idx in 0 until items.length()) {
                    val it = items.optJSONObject(idx) ?: continue
                    val v = it.optJSONObject("playlistVideoRenderer") ?: continue
                    val vid = v.optString("videoId")
                    if (vid.isNullOrBlank()) continue

                    val name = parseRuns(v.optJSONObject("title"))
                    val thumb = "https://i.ytimg.com/vi/$vid/hqdefault.jpg"

                    list += HikariEpisode(
                        number = idx + 1,
                        id = vid,
                        name = if (name.isNotBlank()) name else "Episode ${idx + 1}",
                        image = thumb,
                        season = 1,
                    )
                }
            }
        }
        return list
    }

    override suspend fun getStreams(media: HikariMedia, episode: HikariEpisode?): List<HikariStream> {
        val rawId = episode?.id ?: media.id
        val videoId = when {
            rawId.contains("v=") -> rawId.substringAfter("v=").substringBefore("&")
            rawId.contains("youtu.be/") -> rawId.substringAfter("youtu.be/").substringBefore("?")
            rawId.startsWith("playlist:") -> rawId.removePrefix("playlist:")
            else -> rawId
        }.trim()

        if (videoId.isBlank()) return emptyList()

        return listOf(
            HikariStream(
                name = "YouTube Native Player",
                ytId = videoId,
            ),
            HikariStream(
                name = "YouTube Web Embed",
                url = "https://www.youtube-nocookie.com/embed/$videoId?autoplay=1",
                headers = mapOf("Referer" to "https://www.youtube-nocookie.com/"),
                externalUrl = false,
            ),
            HikariStream(
                name = "YouTube Mobile Web",
                url = "https://m.youtube.com/watch?v=$videoId",
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                ),
                externalUrl = true,
            ),
            HikariStream(
                name = "YouTube Desktop Web",
                url = "https://www.youtube.com/watch?v=$videoId",
                headers = mapOf("Referer" to "https://www.youtube.com/"),
                externalUrl = true,
            ),
        )
    }

    private fun parseRuns(obj: JSONObject?): String {
        if (obj == null) return ""
        val simpleText = obj.optString("simpleText")
        if (simpleText.isNotBlank()) return simpleText.trim()

        val runs = obj.optJSONArray("runs") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until runs.length()) {
            val r = runs.optJSONObject(i) ?: continue
            sb.append(r.optString("text"))
        }
        return sb.toString().trim()
    }

    private fun parseSnippets(arr: JSONArray?): String {
        if (arr == null || arr.length() == 0) return ""
        val first = arr.optJSONObject(0) ?: return ""
        val snippetText = first.optJSONObject("snippetText") ?: return ""
        return parseRuns(snippetText)
    }

    private fun unescape(s: String): String = s
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#039;", "'")
        .replace("&#39;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&nbsp;", " ")
        .replace(Regex("""\s+"""), " ")
        .trim()
}
