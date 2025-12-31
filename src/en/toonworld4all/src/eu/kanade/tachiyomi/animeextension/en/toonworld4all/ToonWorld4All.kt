package eu.kanade.tachiyomi.animeextension.en.toonworld4all

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import uy.kohesive.injekt.injectLazy

class ToonWorld4All : AnimeHttpSource() {

    override val name = "ToonWorld4All"

    override val baseUrl = "https://toonworld4all.me"

    override val lang = "en"

    override val supportsLatest = true

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
        
        val hasNextPage = document.select("nav.herald-pagination a.next").isNotEmpty() ||
            document.select("nav.herald-pagination a.page-numbers").any { 
                it.text().toIntOrNull()?.let { page -> 
                    val current = document.select("nav.herald-pagination span.current").text().toIntOrNull() ?: 1
                    page > current
                } ?: false
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
        
        // Find links pointing to archive.toonworld4all.me
        document.select("a[href*='archive.toonworld4all.me']").forEach { element ->
            val ep = SEpisode.create().apply {
                url = element.attr("href")
                name = element.parent().previousElementSibling()?.text() ?: element.text()
                if (name.contains("Watch/Download", ignoreCase = true)) {
                    // Try to find a better name from the accordion or heading
                    name = element.closest(".mks_accordion_item")?.select(".mks_accordion_heading")?.text() 
                        ?: element.text()
                }
            }
            episodes.add(ep)
        }
        
        return episodes.reversed()
    }

    // Video Links (The "Hard" Part)
    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val response = client.newCall(GET(episode.url, headers)).execute()
        val document = response.asJsoup()
        
        val propsJson = document.select("script").html()
            .substringAfter("window.__PROPS__ = ")
            .substringBefore(";")
        
        if (propsJson.isEmpty()) return emptyList()
        
        val props = json.decodeFromString<EpisodeProps>(propsJson)
        val videos = mutableListOf<Video>()
        
        props.data.data.encodes.forEach { encode ->
            encode.files.forEach { file ->
                val redirectUrl = if (file.link.startsWith("/")) "$archiveUrl${file.link}" else file.link
                
                // Get the final link from the redirect page
                val finalLink = fetchFinalLink(redirectUrl)
                if (finalLink != null) {
                    val quality = "${encode.resolution} - ${file.host}"
                    // Here we would ideally use an extractor, but for now we just return the host link
                    // In a real extension, we would use: HubCloudExtractor(client).videoFromUrl(finalLink)
                    videos.add(Video(finalLink, quality, finalLink, headers))
                }
            }
        }
        
        return videos
    }

    private fun fetchFinalLink(url: String): String? {
        return try {
            val response = client.newCall(GET(url, headers)).execute()
            val html = response.body.string()
            val propsJson = html.substringAfter("window.__PROPS__ = ").substringBefore(";")
            val props = json.decodeFromString<RedirectProps>(propsJson)
            val finalLink = "${props.link.domain}${props.link.hidden}"
            response.close()
            finalLink
        } catch (e: Exception) {
            null
        }
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

    @Serializable
    data class RedirectProps(
        val link: RedirectLink
    )

    @Serializable
    data class RedirectLink(
        val domain: String,
        val hidden: String
    )

    override fun animeDetailsRequest(anime: SAnime): Request = GET(anime.url, headers)
    override fun episodeListRequest(anime: SAnime): Request = GET(anime.url, headers)
    override fun videoListRequest(episode: SEpisode): Request = GET(episode.url, headers)
}
