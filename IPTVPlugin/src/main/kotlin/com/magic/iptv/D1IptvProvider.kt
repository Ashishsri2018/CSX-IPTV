package com.magic.iptv

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.app 
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import java.net.URLEncoder
import java.net.URLDecoder

// 1. Data classes mapped to your exact router.js outputs
data class SourceData(
    @JsonProperty("id") val id: String?,
    @JsonProperty("name") val name: String?
)

data class CategoriesResponse(
    @JsonProperty("categories") val categories: Array<CategoryData>?
)

data class CategoryData(
    @JsonProperty("name") val name: String?,
    @JsonProperty("count") val count: Int?
)

data class ChannelsResponse(
    @JsonProperty("data") val data: Array<ChannelData>?
)

data class ChannelData(
    @JsonProperty("name") val name: String?,
    @JsonProperty("logo_url") val logoUrl: String?,
    @JsonProperty("stream_url") val streamUrl: String?,
    @JsonProperty("channel_group") val channelGroup: String?
)

class D1IptvProvider : MainAPI() {
    override var mainUrl = "https://iptv-hub.ashishsri2018.workers.dev" 
    override var name = "My Cloudflare IPTV" 
    override val hasMainPage = true
    override var sequentialMainPage = true 
    override var lang = "en"
    override val supportedTypes = setOf(TvType.TvSeries)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        if (page > 1) return null 

        val homePageLists = mutableListOf<HomePageList>()
        
        // 1. LOAD MASSIVE IMPORTED PLAYLISTS (Rows = Playlist, Items = Categories)
        try {
            val response = app.get("$mainUrl/api/sources").text
            val sources = parseJson<Array<SourceData>>(response)
            
            for (source in sources) {
                val sourceId = source.id ?: continue
                val sourceName = source.name ?: "Unknown Playlist"
                
                try {
                    val catResponse = app.get("$mainUrl/api/categories?sourceId=$sourceId").text
                    val catData = parseJson<CategoriesResponse>(catResponse)
                    val categoryList = catData.categories ?: emptyArray()
                    
                    val searchItems = categoryList.mapNotNull { category ->
                        val catName = category.name ?: "Uncategorized"
                        
                        val encodedCatName = URLEncoder.encode(catName, "UTF-8")
                        val encodedSourceName = URLEncoder.encode(sourceName, "UTF-8")
                        
                        // Pack URL to route properly on the next screen
                        val safeUrl = "$mainUrl/virtual?id=$sourceId&category=$encodedCatName&sourceName=$encodedSourceName&isCustom=false"

                        newTvSeriesSearchResponse(catName, safeUrl, TvType.TvSeries) {
                            this.posterUrl = null 
                        }
                    }

                    if (searchItems.isNotEmpty()) {
                        homePageLists.add(HomePageList(sourceName, searchItems, isHorizontalImages = true))
                    }
                } catch (e: Exception) {
                    // Silently skips a broken playlist so others can load
                }
            }
        } catch (e: Exception) {}

        // 2. LOAD CUSTOM PLAYLISTS (Row = "Custom Playlists", Items = Playlists)
        try {
            val response = app.get("$mainUrl/api/playlists").text
            val customPlaylists = parseJson<Array<SourceData>>(response)
            
            val searchItems = customPlaylists.mapNotNull { playlist ->
                val playlistId = playlist.id ?: return@mapNotNull null
                val playlistName = playlist.name ?: "Unknown Playlist"
                
                val encodedName = URLEncoder.encode(playlistName, "UTF-8")
                val safeUrl = "$mainUrl/virtual?id=$playlistId&name=$encodedName&isCustom=true"

                newTvSeriesSearchResponse(playlistName, safeUrl, TvType.TvSeries) {
                    this.posterUrl = null 
                }
            }

            if (searchItems.isNotEmpty()) {
                homePageLists.add(HomePageList("Custom Playlists", searchItems, isHorizontalImages = true))
            }
        } catch (e: Exception) {}

        return newHomePageResponse(homePageLists, false)
    }

    override suspend fun load(url: String): LoadResponse? {
        val id = Regex("id=([^&]+)").find(url)?.groupValues?.get(1) ?: return null
        val isCustom = Regex("isCustom=([^&]+)").find(url)?.groupValues?.get(1) == "true"
        
        val episodesList = mutableListOf<Episode>()
        var displayTitle = ""

        try {
            if (isCustom) {
                // Fetch channels for a Custom Playlist
                val name = Regex("name=([^&]+)").find(url)?.groupValues?.get(1)?.let { URLDecoder.decode(it, "UTF-8") } ?: "Custom List"
                displayTitle = name
                
                val response = app.get("$mainUrl/api/playlists/$id/channels").text
                val channels = parseJson<Array<ChannelData>>(response)
                
                channels.forEach { channel ->
                    val streamUrl = channel.streamUrl ?: return@forEach
                    episodesList.add(newEpisode(streamUrl) {
                        this.name = channel.name ?: "Unknown Channel"
                        this.posterUrl = channel.logoUrl
                        this.season = 1
                    })
                }
            } else {
                // Fetch channels for a specific Category (Never hits the 20,000 channel limit!)
                val category = Regex("category=([^&]+)").find(url)?.groupValues?.get(1)?.let { URLDecoder.decode(it, "UTF-8") } ?: "Uncategorized"
                val sourceName = Regex("sourceName=([^&]+)").find(url)?.groupValues?.get(1)?.let { URLDecoder.decode(it, "UTF-8") } ?: "Live TV"
                displayTitle = "$sourceName - $category"
                
                val encodedCat = URLEncoder.encode(category, "UTF-8")
                val response = app.get("$mainUrl/api/channels?sourceId=$id&category=$encodedCat&limit=2000").text
                val channelsData = parseJson<ChannelsResponse>(response)
                val channels = channelsData.data ?: emptyArray()
                
                channels.forEach { channel ->
                    val streamUrl = channel.streamUrl ?: return@forEach
                    episodesList.add(newEpisode(streamUrl) {
                        this.name = channel.name ?: "Unknown Channel"
                        this.posterUrl = channel.logoUrl
                        this.season = 1
                    })
                }
            }
        } catch (e: Exception) {
            // Failsafe
        }

        return newTvSeriesLoadResponse(
            displayTitle,
            url,
            TvType.TvSeries,
            episodesList
        )
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        
        val linkType = if (data.contains(".m3u8", ignoreCase = true)) {
            ExtractorLinkType.M3U8
        } else {
            ExtractorLinkType.VIDEO
        }

        callback.invoke(
            newExtractorLink(
                this.name,
                this.name,
                data,
                linkType
            ) {
                this.quality = Qualities.Unknown.value
            }
        )
        return true
    }
}
