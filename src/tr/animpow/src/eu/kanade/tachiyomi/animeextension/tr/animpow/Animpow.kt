package eu.kanade.tachiyomi.animeextension.tr.animpow

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element

class Animpow : AnimeHttpSource() {

    override val name = "Animpow"

    override val baseUrl = "https://animpow.com"

    override val lang = "tr"

    override val supportsLatest = true

    override val headers = super.headers.newBuilder()
        .set("Referer", "$baseUrl/")
        .build()

    private val webViewResolver by lazy { AnimpowWebViewResolver(headers) }

    override fun popularAnimeRequest(page: Int): Request = catalogRequest(page, "popularity")

    override fun popularAnimeParse(response: Response): AnimesPage = catalogParse(response)

    override fun latestUpdatesRequest(page: Int): Request = catalogRequest(page, "year")

    override fun latestUpdatesParse(response: Response): AnimesPage = catalogParse(response)

    private fun catalogRequest(page: Int, sort: String): Request {
        val url = "$baseUrl/catalog".toHttpUrl().newBuilder()
            .addQueryParameter("siralama", sort)
            .addQueryParameter("sayfa", page.toString())
            .build()
        return GET(url, headers)
    }

    private fun catalogParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val anime = document.select("a.anime-card[href^=/anime/]")
            .mapNotNull(::animeFromCard)
            .distinctBy { it.url }
        val hasNextPage = document.selectFirst(".pagination a[href*='sayfa=']")
            ?.text()
            ?.contains("Sonraki", ignoreCase = true) == true

        return AnimesPage(anime, hasNextPage)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.isBlank()) return popularAnimeRequest(page)

        val url = "$baseUrl/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .build()
        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        if (response.request.url.encodedPath == "/catalog") return catalogParse(response)

        val anime = response.asJsoup()
            .select("a[href^=/anime/]:has(.anime-card)")
            .mapNotNull(::animeFromCard)
            .distinctBy { it.url }
        return AnimesPage(anime, false)
    }

    private fun animeFromCard(anchor: Element): SAnime? {
        val card = if (anchor.hasClass("anime-card")) anchor else anchor.selectFirst(".anime-card") ?: return null
        val title = card.selectFirst(".anime-card-title")?.text()
            ?: card.selectFirst("img[alt]")?.attr("alt")
            ?: return null

        return SAnime.create().apply {
            this.title = title
            url = anchor.attr("href")
            thumbnail_url = card.selectFirst("img.anime-card-poster")?.absUrl("src")
                ?.ifBlank { card.selectFirst("img.anime-card-poster")?.attr("src") }
                .orEmpty()
            status = statusFromText(card.text())
        }
    }

    override fun animeDetailsRequest(anime: SAnime): Request = GET(anime.url.absoluteUrl(), headers)

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        val metaText = document.selectFirst("[class^=anime_anime-meta-row]")?.text().orEmpty()

        return SAnime.create().apply {
            title = document.selectFirst("h1[class^=anime_anime-title]")?.text().orEmpty()
            thumbnail_url = document.selectFirst("img[src*=image-cdn]")?.absUrl("src")
                ?.ifBlank { document.selectFirst("img[src*=image-cdn]")?.attr("src") }
                .orEmpty()
            description = document.selectFirst("p[class^=anime_anime-desc]")?.text()
            genre = document.select("[class^=anime_anime-genres] .badge")
                .joinToString { it.text() }
            status = statusFromText(metaText)
        }
    }

    override fun episodeListRequest(anime: SAnime): Request = animeDetailsRequest(anime)

    override fun episodeListParse(response: Response): List<SEpisode> {
        return response.asJsoup()
            .select("a[href^=/watch/]:has(span[class^=anime_ep-num])")
            .mapNotNull { element ->
                val href = element.attr("href")
                val match = EPISODE_REGEX.find(href) ?: return@mapNotNull null
                val season = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                val episodeNumber = match.groupValues[2].toFloatOrNull() ?: return@mapNotNull null
                val episodeTitle = element.selectFirst("span[class^=anime_ep-name]")?.text()
                    ?.takeIf(String::isNotBlank)

                SEpisode.create().apply {
                    name = buildString {
                        append("Sezon ")
                        append(season)
                        append(" - ")
                        append(episodeTitle ?: "Bölüm ${formatNumber(episodeNumber)}")
                    }
                    url = href
                    episode_number = episodeNumber
                }
            }
            .distinctBy { it.url }
            .sortedWith(
                compareByDescending<SEpisode> { EPISODE_REGEX.find(it.url)?.groupValues?.get(1)?.toIntOrNull() ?: 0 }
                    .thenByDescending { it.episode_number },
            )
    }

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val result = webViewResolver.resolve(episode.url.absoluteUrl())
        result.error?.let { throw Exception(it) }
        val streamUrl = result.url ?: throw Exception("Animpow video bağlantısı alınamadı. Lütfen tekrar deneyin.")

        val videoHeaders = headers.newBuilder().apply {
            result.cookie?.takeIf(String::isNotBlank)?.let { set("Cookie", it) }
        }.build()

        return listOf(
            Video(
                url = streamUrl,
                quality = "Animpow CDN${result.quality?.let { " - $it" }.orEmpty()}",
                videoUrl = streamUrl,
                headers = videoHeaders,
            ),
        )
    }

    private fun String.absoluteUrl(): String = if (startsWith("http")) this else "$baseUrl$this"

    private fun statusFromText(text: String): Int = when {
        text.contains("Tamamlandı", ignoreCase = true) -> SAnime.COMPLETED
        text.contains("Yayında", ignoreCase = true) -> SAnime.ONGOING
        else -> SAnime.UNKNOWN
    }

    private fun formatNumber(value: Float): String = if (value % 1F == 0F) value.toInt().toString() else value.toString()

    companion object {
        private val EPISODE_REGEX = Regex("/watch/\\d+/s(\\d+)e([\\d.]+)")
    }
}
