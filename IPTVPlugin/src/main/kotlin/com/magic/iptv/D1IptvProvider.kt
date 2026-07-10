package com.magic.iptv

import android.net.Uri
import com.lagradost.api.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URLEncoder
import java.net.URLDecoder

@JsonIgnoreProperties(ignoreUnknown = true)
data class SourceData(
    @JsonProperty("id") val id: String?,
    @JsonProperty("name") val name: String?
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CategoriesResponse(
    @JsonProperty("categories") val categories: Array<CategoryData>?
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CategoryData(
    @JsonProperty("name") val name: String?,
    @JsonProperty("count") val count: Int?
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ChannelsResponse(
    @JsonProperty("data") val data: Array<ChannelData>?
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ChannelData(
    @JsonProperty("id") val id: String?,
    @JsonProperty("source_id") val sourceId: String?, 
    @JsonProperty("name") val name: String?,
    @JsonProperty("logo_url") val logoUrl: String?,
    @JsonProperty("stream_url") val streamUrl: String?,
    @JsonProperty("channel_group") val channelGroup: String?,
    @JsonProperty("http_referer") val httpReferer: String?,
    @JsonProperty("http_user_agent") val httpUserAgent: String?,
    @JsonProperty("http_headers") val httpHeaders: Map<String, String>?
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PlaybackData(
    @JsonProperty("url") val url: String,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("referer") val referer: String? = null,
    @JsonProperty("userAgent") val userAgent: String? = null,
    @JsonProperty("headers") val headers: Map<String, String>? = null
)

class D1IptvProvider : MainAPI() {
    override var mainUrl = "https://iptv-hub.ashishsri2018.workers.dev"
    override var name = "My Cloudflare IPTV"
    override val hasMainPage = true
    override val hasQuickSearch = true 
    override var sequentialMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Live)

    private val channelFetchLimit = 10000

    private fun queryParams(url: String): Map<String, String> {
        val query = url.substringAfter('?', "")
        if (query.isEmpty()) return emptyMap()
        return query.split("&").mapNotNull { pair ->
            val idx = pair.indexOf('=')
            if (idx == -1) return@mapNotNull null
            val key = pair.substring(0, idx)
            val rawValue = pair.substring(idx + 1)
            val value = try {
                URLDecoder.decode(rawValue, "UTF-8")
            } catch (e: Exception) {
                rawValue
            }
            key to value
        }.toMap()
    }

    private fun avatarFor(label: String): String {
        val lower = label.lowercase()
        return when {
            lower.contains("movie") || lower.contains("film") || lower.contains("cinema") -> "https://images.unsplash.com/photo-1536440136628-849c177e76a1?w=512&q=80&fit=crop"
            lower.contains("sport") || lower.contains("football") || lower.contains("soccer") || lower.contains("basketball") || lower.contains("baseball") || lower.contains("nfl") || lower.contains("nba") -> "https://images.unsplash.com/photo-1461896836934-ffe140ba5a0d?w=512&q=80&fit=crop"
            lower.contains("news") || lower.contains("journal") || lower.contains("weather") -> "https://images.unsplash.com/photo-1495020689067-958852a7765e?w=512&q=80&fit=crop"
            lower.contains("music") || lower.contains("radio") || lower.contains("mtv") || lower.contains("songs") -> "https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?w=512&q=80&fit=crop"
            lower.contains("kids") || lower.contains("child") || lower.contains("cartoon") || lower.contains("animation") || lower.contains("disney") || lower.contains("nickelodeon") -> "https://images.unsplash.com/photo-1585644171261-264627d704ba?w=512&q=80&fit=crop"
            lower.contains("documentary") || lower.contains("nature") || lower.contains("history") || lower.contains("discovery") -> "https://images.unsplash.com/photo-1518182170546-076616fd4aa8?w=512&q=80&fit=crop"
            lower.contains("comedy") || lower.contains("laugh") || lower.contains("funny") -> "https://images.unsplash.com/photo-1527228117206-44eb1c28c68c?w=512&q=80&fit=crop"
            lower.contains("science") || lower.contains("tech") -> "https://images.unsplash.com/photo-1532094349884-543bc11b234d?w=512&q=80&fit=crop"
            lower.contains("education") || lower.contains("learning") -> "https://images.unsplash.com/photo-1503676260728-1c00da094a0b?w=512&q=80&fit=crop"
            lower.contains("favorite") || lower.contains("star") -> "https://images.unsplash.com/photo-1518331647614-7a1f04cd34cf?w=512&q=80&fit=crop"
            else -> {
                val encoded = URLEncoder.encode(label, "UTF-8")
                "https://ui-avatars.com/api/?name=$encoded&background=random&color=fff&size=512&font-size=0.4"
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        if (page > 1) return null

        val homePageLists = mutableListOf<HomePageList>()

        // 1. LOAD MASSIVE IMPORTED PLAYLISTS
        try {
            val response = app.get("$mainUrl/api/sources").text
            val sources = parseJson<Array<SourceData>>(response)

            val rows = coroutineScope {
                sources.map { source ->
                    async {
                        val sourceId = source.id ?: return@async null
                        val sourceName = source.name ?: "Unknown Playlist"

                        try {
                            val catResponse = app.get("$mainUrl/api/categories?sourceId=$sourceId").text
                            val catData = parseJson<CategoriesResponse>(catResponse)
                            val categoryList = catData.categories ?: emptyArray()

                            val searchItems = categoryList.mapNotNull { category ->
                                val catName = category.name ?: "Uncategorized"
                                val encodedCatName = URLEncoder.encode(catName, "UTF-8")
                                val encodedSourceName = URLEncoder.encode(sourceName, "UTF-8")

                                val safeUrl = "$mainUrl/virtual?id=$sourceId&category=$encodedCatName&sourceName=$encodedSourceName&isCustom=false"

                                newTvSeriesSearchResponse(catName, safeUrl, TvType.TvSeries) {
                                    this.posterUrl = avatarFor(catName)
                                }
                            }

                            if (searchItems.isNotEmpty()) {
                                HomePageList(sourceName, searchItems, isHorizontalImages = true)
                            } else null
                        } catch (e: Exception) {
                            null
                        }
                    }
                }.awaitAll()
            }

            homePageLists.addAll(rows.filterNotNull())
        } catch (e: Exception) { }

        // 2. LOAD CUSTOM PLAYLISTS (Includes unified favorites!)
        try {
            val response = app.get("$mainUrl/api/playlists").text
            val customPlaylists = parseJson<Array<SourceData>>(response)
            
            val searchItems = customPlaylists.mapNotNull { playlist ->
                val playlistId = playlist.id ?: return@mapNotNull null
                val playlistName = playlist.name ?: "Unknown Playlist"

                val encodedName = URLEncoder.encode(playlistName, "UTF-8")
                val safeUrl = "$mainUrl/virtual?id=$playlistId&name=$encodedName&isCustom=true"

                newTvSeriesSearchResponse(playlistName, safeUrl, TvType.TvSeries) {
                    this.posterUrl = avatarFor(playlistName)
                }
            }

            if (searchItems.isNotEmpty()) {
                homePageLists.add(HomePageList("Custom Playlists", searchItems, isHorizontalImages = true))
            }
        } catch (e: Exception) {}

        return newHomePageResponse(homePageLists, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val searchUrl = "$mainUrl/api/channels?search=$encodedQuery&limit=100"
            
            val response = app.get(searchUrl).text
            val channelsData = parseJson<ChannelsResponse>(response)
            val channels = channelsData.data ?: emptyArray()

            channels.mapNotNull { channel ->
                val streamUrl = channel.streamUrl ?: return@mapNotNull null
                val channelName = channel.name ?: "Unknown Channel"

                val payload = PlaybackData(
                    url = streamUrl,
                    name = channelName,
                    referer = channel.httpReferer,
                    userAgent = channel.httpUserAgent,
                    headers = channel.httpHeaders
                )

                newLiveSearchResponse(channelName, payload.toJson(), TvType.Live) {
                    this.posterUrl = channel.logoUrl ?: avatarFor(channelName)
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        if (url.startsWith("{") && url.contains("\"url\"")) {
            val playback = parseJson<PlaybackData>(url)
            val channelName = playback.name ?: "Live Channel"
            return newLiveStreamLoadResponse(
                channelName,
                url, 
                url  
            ) {
                this.posterUrl = avatarFor(channelName)
            }
        }

        val params = queryParams(url)
        val id = params["id"] ?: return null
        
        val isCustom = url.contains("isCustom=true")

        val episodesList = mutableListOf<Episode>()
        var displayTitle = ""

        try {
            if (isCustom) {
                displayTitle = params["name"] ?: "Custom List"
                val response = app.get("$mainUrl/api/playlists/$id/channels?t=${System.currentTimeMillis()}").text
                val channels = parseJson<Array<ChannelData>>(response)
                episodesList.addAll(channels.mapNotNull { toEpisode(it) })
            } else {
                val category = params["category"] ?: "Uncategorized"
                val sourceName = params["sourceName"] ?: "Live TV"
                displayTitle = "$sourceName - $category"

                val encodedCat = URLEncoder.encode(category, "UTF-8")
                val response = app.get("$mainUrl/api/channels?sourceId=$id&category=$encodedCat&limit=$channelFetchLimit&t=${System.currentTimeMillis()}").text
                val channelsData = parseJson<ChannelsResponse>(response)
                val channels = channelsData.data ?: emptyArray()

                episodesList.addAll(channels.mapNotNull { toEpisode(it) })
            }
        } catch (e: Exception) {
            Log.e("D1Iptv", "Error loading episodes list: ${e.message}")
        }

        return newTvSeriesLoadResponse(
            displayTitle,
            url,
            TvType.TvSeries,
            episodesList
        ) {
            this.posterUrl = avatarFor(displayTitle)
        }
    }

    private fun toEpisode(channel: ChannelData): Episode? {
        val streamUrl = channel.streamUrl ?: return null
        val channelName = channel.name ?: "Unknown Channel"

        val payload = PlaybackData(
            url = streamUrl,
            name = channelName,
            referer = channel.httpReferer,
            userAgent = channel.httpUserAgent,
            headers = channel.httpHeaders
        )

        return newEpisode(payload.toJson()) {
            this.name = channelName
            this.posterUrl = channel.logoUrl
            this.season = 1
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val playback = try {
            parseJson<PlaybackData>(data)
        } catch (e: Exception) {
            PlaybackData(url = data)
        }

        val streamUrl = playback.url
        val requestHeaders = buildMap {
            playback.headers?.let { putAll(it) }
            playback.userAgent?.let { put("User-Agent", it) }
        }
        val referer = playback.referer ?: ""
        val channelName = playback.name ?: this.name

        var isM3u8 = streamUrl.contains(".m3u8", ignoreCase = true)

        if (!isM3u8 && !streamUrl.contains(".mp4", ignoreCase = true) &&
            !streamUrl.contains(".mkv", ignoreCase = true) && !streamUrl.contains(".ts", ignoreCase = true)) {
            try {
                val response = withTimeoutOrNull(4000L) {
                    app.head(streamUrl, headers = requestHeaders, referer = referer)
                }

                val contentType = (response?.headers?.get("Content-Type")
                    ?: response?.headers?.get("content-type") ?: "").lowercase()

                if (contentType.contains("mpegurl") || contentType.contains("m3u8") || contentType.contains("application/x-mpegurl")) {
                    isM3u8 = true
                }
            } catch (e: Exception) { }
        }

        val linkType = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO

        callback.invoke(
            newExtractorLink(
                this.name,
                channelName, 
                streamUrl,
                linkType
            ) {
                this.quality = Qualities.Unknown.value
                this.referer = referer
                this.headers = requestHeaders.ifEmpty { mapOf("User-Agent" to "IPTVSmarters/1.0") }
            }
        )
        return true
    }
}
