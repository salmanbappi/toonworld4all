package eu.kanade.tachiyomi.animeextension.en.toonworld4all

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.cloudflareinterceptor.CloudflareInterceptor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Dispatcher
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.injectLazy
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalSerializationApi::class)
class ToonWorld4All : AnimeHttpSource() {

    override val name = "ToonWorld4All"

    override val baseUrl = "https://toonworld4all.me"

    override val lang = "en"

    override val supportsLatest = true

    override val client: OkHttpClient = super.client.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .addInterceptor(CloudflareInterceptor(super.client))
        .build()

    private val json: Json by injectLazy()

    private val archiveUrl = "https://archive.toonworld4all.me"

    override fun headersBuilder() = super.headersBuilder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .add("Referer", "$baseUrl/")

    // Popular Anime
    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/page/$page", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animeList = document.select("article.herald-lay-b, article.herald-fa-item").map { element ->
            SAnime.create().apply {
                title = element.select("h2.entry-title a").text()
                url = element.select("h2.entry-title a").attr("href")
                thumbnail_url = element.select("img.wp-post-image").attr("src")
            }
        }
        
        val current = document.select("nav.herald-pagination span.current").text().toIntOrNull() ?: 1
        val hasNextPage = document.select("nav.herald-pagination a.next").isNotEmpty() ||
            document.select("nav.herald-pagination a.page-numbers").any { 
                it.text().toIntOrNull()?.let { page -> page > current } ?: false
            }
        
        return AnimesPage(animeList, hasNextPage)
    }

    // Latest Updates
    override fun latestUpdatesRequest(page: Int): Request = popularAnimeRequest(page)
    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    // Search
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return GET("$baseUrl/page/$page/?s=$query", headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    // Details
    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        return SAnime.create().apply {
            title = document.select("h1.entry-title").text()
            description = document.select("div.herald-entry-content p").text()
            genre = document.select("span.meta-category a").joinToString { it.text() }
            status = SAnime.UNKNOWN
        }
    }

    // Episodes
    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodes = mutableListOf<SEpisode>()
        
        document.select("a[href*='archive.toonworld4all.me']").forEach { element ->
            val ep = SEpisode.create().apply {
                url = element.attr("href")
                name = element.closest(".mks_accordion_item")?.select(".mks_accordion_heading")?.text() 
                    ?: element.parent().previousElementSibling()?.text() 
                    ?: element.text()
            }
            episodes.add(ep)
        }
        
        return episodes.reversed()
    }

    // Video Links (Direct Redirect strategy)
    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val response = client.newCall(GET(episode.url, headers)).execute()
        val document = response.asJsoup()
        
        val scriptContent = document.select("script").html()
        val propsJson = scriptContent.substringAfter("window.__PROPS__ = ", "")
            .substringBefore(";", "")
        
        if (propsJson.isEmpty()) return emptyList()
        
        val props = try {
            json.decodeFromString<EpisodeProps>(propsJson)
        } catch (e: Exception) {
            return emptyList()
        }

        val videos = props.data.data.encodes.flatMap { encode ->
            encode.files.map { file ->
                val redirectUrl = if (file.link.startsWith("/")) "$archiveUrl${file.link}" else file.link
                val quality = "${encode.resolution} - ${file.host}"
                // We return the redirect URL directly. 
                // Aniyomi will try to play it, failing which the user can use 'Open in Webview'
                // This is the only stable way for ToonWorld4All's current protection.
                Video(redirectUrl, quality, redirectUrl, headers)
            }
        }
        
        return videos.sortedWith(compareByDescending<Video> { it.quality.contains("1080") }
            .thenByDescending { it.quality.contains("720") }
        )
    }

    @Serializable
    data class EpisodeProps(
        val data: EpisodeDataWrapper
    )

    @Serializable
    data class EpisodeDataWrapper(
        val data: EpisodeData
    )

    @Serializable
    data class EpisodeData(
        val encodes: List<Encode>
    )

    @Serializable
    data class Encode(
        val resolution: String,
        val is_hq: Boolean,
        val files: List<FileItem>
    )

    @Serializable
    data class FileItem(
        val host: String,
        val link: String
    )

    override fun animeDetailsRequest(anime: SAnime): Request = GET(anime.url, headers)
    override fun episodeListRequest(anime: SAnime): Request = GET(anime.url, headers)
    override fun videoListRequest(episode: SEpisode): Request = GET(episode.url, headers)
}