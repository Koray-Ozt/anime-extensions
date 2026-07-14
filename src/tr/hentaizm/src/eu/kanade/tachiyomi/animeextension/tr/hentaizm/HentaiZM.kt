package eu.kanade.tachiyomi.animeextension.tr.hentaizm

import android.util.Base64
import aniyomi.lib.omniembedextractor.OmniEmbedExtractor
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animeextension.tr.hentaizm.extractors.VideaExtractor
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class HentaiZM : AnimeHttpSource() {

    override val name = "HentaiZM"

    override val baseUrl = "https://www.hentaizm1.com"

    override val lang = "tr"

    override val supportsLatest = true

    private val sourceHeaders = headers.newBuilder()
        .set("Referer", "$baseUrl/")
        .build()

    private val ajaxHeaders = sourceHeaders.newBuilder()
        .set("Accept", "application/json, text/javascript, */*; q=0.01")
        .set("X-Requested-With", "XMLHttpRequest")
        .build()

    private val playlistUtils by lazy { PlaylistUtils(client, sourceHeaders) }
    private val webViewResolver by lazy { HentaizmWebViewResolver(sourceHeaders) }

    override fun popularAnimeRequest(page: Int): Request = apiRequest("/api/top_favorites_list.php", page)

    override fun popularAnimeParse(response: Response): AnimesPage = apiPage(response, ::popularCard)

    override fun latestUpdatesRequest(page: Int): Request = apiRequest("/api/episodes_list.php", page)

    override fun latestUpdatesParse(response: Response): AnimesPage = apiPage(response, ::latestCard)

    private fun apiRequest(path: String, page: Int, query: String? = null): Request {
        val url = "$baseUrl$path".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .apply { query?.let { addQueryParameter("q", it) } }
            .build()
        return GET(url, ajaxHeaders)
    }

    private fun apiPage(response: Response, parser: (Element) -> SAnime?): AnimesPage {
        val json = JSONObject(response.body.string())
        val document = Jsoup.parseBodyFragment(json.optString("html"), baseUrl)
        val anime = document.select(".video-list-item")
            .mapNotNull(parser)
            .distinctBy { it.url }
        val page = json.optInt("page", 1)
        val totalPages = json.optInt("total_pages", page)
        return AnimesPage(anime, page < totalPages)
    }

    private fun popularCard(card: Element): SAnime? {
        val anchor = card.selectFirst(".title a[href*=/anime/]") ?: return null
        val title = anchor.ownText().trim().ifBlank {
            card.selectFirst("img[alt]")?.attr("alt").orEmpty()
        }
        if (title.isBlank()) return null

        return SAnime.create().apply {
            this.title = title
            url = relativeUrl(anchor.absUrl("href").ifBlank { anchor.attr("href") })
            thumbnail_url = card.selectFirst(".img img[src]")?.absUrl("src")
                ?.ifBlank { card.selectFirst(".img img[src]")?.attr("src") }
                .orEmpty()
            description = card.selectFirst(".content p")?.text()
        }
    }

    private fun latestCard(card: Element): SAnime? {
        val animeAnchor = card.selectFirst("a.video-name[href*=/anime/]") ?: return null
        val episodeAnchor = card.selectFirst(".title > a[href*=/izle/]") ?: return null
        val fullEpisodeTitle = episodeAnchor.clone().apply { select("img").remove() }.text().trim()
        val title = fullEpisodeTitle.replace(EPISODE_TITLE_SUFFIX, "").trim()
            .ifBlank { animeAnchor.text().trim() }
        if (title.isBlank()) return null

        val animeUrl = relativeUrl(animeAnchor.absUrl("href").ifBlank { animeAnchor.attr("href") })
        return SAnime.create().apply {
            this.title = title
            url = animeUrl
            thumbnail_url = posterFromAnimeUrl(animeUrl)
        }
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.isBlank()) return popularAnimeRequest(page)
        if (query.startsWith("$baseUrl/anime/")) return GET(query, sourceHeaders)

        val url = "$baseUrl/api/sidebar_anime_list.php".toHttpUrl().newBuilder()
            .addQueryParameter("view_type", "card")
            .addQueryParameter("q", query)
            .build()
        return GET(url, ajaxHeaders)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        if (response.request.url.encodedPath == "/api/top_favorites_list.php") {
            return popularAnimeParse(response)
        }
        if (response.request.url.encodedPath.startsWith("/anime/")) {
            val url = relativeUrl(response.request.url.toString())
            val anime = animeDetailsParse(response).apply { this.url = url }
            return AnimesPage(listOf(anime), false)
        }

        val json = JSONObject(response.body.string())
        val document = Jsoup.parseBodyFragment(json.optString("html"), baseUrl)
        val anime = document.select("h3 a[href*=/anime/]").mapNotNull { anchor ->
            val title = anchor.text().trim().takeIf(String::isNotBlank) ?: return@mapNotNull null
            val animeUrl = relativeUrl(anchor.absUrl("href").ifBlank { anchor.attr("href") })
            SAnime.create().apply {
                this.title = title
                url = animeUrl
                thumbnail_url = posterFromAnimeUrl(animeUrl)
            }
        }.distinctBy { it.url }

        return AnimesPage(anime, false)
    }

    override fun animeDetailsRequest(anime: SAnime): Request = GET(anime.url.absoluteUrl(), sourceHeaders)

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        val pageUrl = relativeUrl(response.request.url.toString())
        val endDate = labelValue(document, "Bitiş Tarihi")

        return SAnime.create().apply {
            title = labelValue(document, "Anime Adı")
                ?: document.selectFirst("h1")?.text()
                ?: document.title().substringBefore(" - ")
            thumbnail_url = document.selectFirst("img[src*=/uploads/afis/]")?.absUrl("src")
                ?.ifBlank { null }
                ?: posterFromAnimeUrl(pageUrl)
            author = labelValue(document, "Stüdyo")
            genre = labelValue(document, "Anime Türü")
                ?.takeUnless { it.contains("giriş", ignoreCase = true) }
            description = summary(document)
            status = when {
                endDate.isNullOrBlank() || endDate == "-" -> SAnime.ONGOING
                else -> SAnime.COMPLETED
            }
        }
    }

    override fun episodeListRequest(anime: SAnime): Request = animeDetailsRequest(anime)

    override fun episodeListParse(response: Response): List<SEpisode> {
        return response.asJsoup()
            .select("a[href*=/izle/][href*=sezon-]")
            .mapNotNull { anchor ->
                val href = anchor.absUrl("href").ifBlank { anchor.attr("href") }
                val match = EPISODE_URL.find(href) ?: return@mapNotNull null
                val season = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                val episodeNumber = match.groupValues[2].replace(',', '.').toFloatOrNull()
                    ?: return@mapNotNull null

                SEpisode.create().apply {
                    name = "Sezon $season - Bölüm ${formatNumber(episodeNumber)}"
                    url = relativeUrl(href)
                    episode_number = episodeNumber
                }
            }
            .distinctBy { it.url }
            .sortedWith(
                compareByDescending<SEpisode> {
                    EPISODE_URL.find(it.url)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                }.thenByDescending { it.episode_number },
            )
    }

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val pageUrl = episode.url.absoluteUrl()
        val pageHeaders = sourceHeaders.newBuilder().set("Referer", pageUrl).build()
        val document = client.newCall(GET(pageUrl, pageHeaders)).execute().asJsoup()

        val directVideos = document.select("video[src], video source[src]")
            .mapNotNull { it.absUrl("src").ifBlank { it.attr("src") }.takeIf(String::isNotBlank) }
            .flatMap { videoFromUrl(it, pageUrl, pageHeaders) }
        if (directVideos.isNotEmpty()) return directVideos.distinctBy { it.videoUrl }

        val embedExtractor = OmniEmbedExtractor(client, pageHeaders)
        val embeddedVideos = document.select("iframe[src]")
            .mapNotNull { it.absUrl("src").ifBlank { it.attr("src") }.takeIf(String::isNotBlank) }
            .flatMap { embedExtractor.extractVideos(it, "Hentaizm", emptyList()) }
        if (embeddedVideos.isNotEmpty()) return embeddedVideos.distinctBy { it.videoUrl }

        for (alternative in protectedAlternatives(document).sortedBy(::providerPriority)) {
            val videos = videosFromAlternative(alternative, pageUrl, pageHeaders)
            if (videos.isNotEmpty()) return videos.distinctBy { it.videoUrl }
        }

        val result = webViewResolver.resolve(pageUrl)
        result.error?.let { throw Exception(it) }
        val streamUrl = result.url
            ?: throw Exception("Hentaizm video bağlantısı alınamadı. Lütfen tekrar deneyin.")
        val videoHeaders = pageHeaders.newBuilder().apply {
            result.cookie?.takeIf(String::isNotBlank)?.let { set("Cookie", it) }
        }.build()

        return videoFromUrl(streamUrl, pageUrl, videoHeaders)
    }

    private fun protectedAlternatives(document: Document): List<PlayerAlternative> {
        val encodedProviders = document.select("script")
            .firstOrNull { it.data().contains("__TA_PROVIDERS_ENC") }
            ?.data()
            ?.let { PROVIDERS_REGEX.find(it)?.groupValues?.get(1) }
            ?: return emptyList()
        val providers = runCatching { JSONObject(decodeProtected(encodedProviders)) }.getOrNull()
            ?: return emptyList()

        return document.select("#video-alternatives .alt-btn[data-provider][data-videoid]")
            .mapNotNull { button ->
                val provider = decodeProtected(button.attr("data-provider"))
                val videoId = decodeProtected(button.attr("data-videoid"))
                if (provider.isBlank() || videoId.isBlank()) return@mapNotNull null

                val embedUrl = when (provider) {
                    "cloudmailru" -> "https://cloud.mail.ru/public/$videoId"
                    "videa" -> "https://videa.hu/player?v=$videoId"
                    "okru" -> "https://ok.ru/videoembed/$videoId"
                    else -> {
                        val html = providers.optString(provider).replace("%VIDEOID%", videoId)
                        Jsoup.parseBodyFragment(html, baseUrl)
                            .selectFirst("iframe[src], a[href]")
                            ?.let { it.absUrl("src").ifBlank { it.absUrl("href") } }
                            .orEmpty()
                    }
                }
                if (embedUrl.isBlank()) return@mapNotNull null

                PlayerAlternative(
                    name = button.text().trim().ifBlank { provider },
                    provider = provider,
                    videoId = videoId,
                    embedUrl = embedUrl,
                )
            }
    }

    private fun videosFromAlternative(
        alternative: PlayerAlternative,
        pageUrl: String,
        pageHeaders: Headers,
    ): List<Video> {
        return when (alternative.provider) {
            "videa" -> runCatching {
                VideaExtractor(client).videosFromUrl(alternative.embedUrl)
            }.getOrDefault(emptyList())

            "okru" -> OmniEmbedExtractor(client, pageHeaders)
                .extractVideos(alternative.embedUrl, "Hentaizm - ${alternative.name}", emptyList())

            else -> {
                val result = webViewResolver.resolve(alternative.embedUrl)
                val streamUrl = result.url ?: return emptyList()
                val videoHeaders = pageHeaders.newBuilder()
                    .set("Referer", alternative.embedUrl)
                    .apply {
                        result.cookie?.takeIf(String::isNotBlank)?.let { set("Cookie", it) }
                    }
                    .build()
                runCatching {
                    videoFromUrl(streamUrl, alternative.embedUrl.ifBlank { pageUrl }, videoHeaders)
                }.getOrDefault(emptyList())
            }
        }
    }

    private fun providerPriority(alternative: PlayerAlternative): Int = when (alternative.provider) {
        "cloudmailru" -> 0
        "videa" -> 1
        "okru" -> 2
        "abyss" -> 3
        else -> 4
    }

    private fun decodeProtected(value: String): String = runCatching {
        val padded = value.padEnd(value.length + (4 - value.length % 4) % 4, '=')
        Base64.decode(padded, Base64.URL_SAFE or Base64.NO_WRAP)
            .toString(Charsets.UTF_8)
            .reversed()
    }.getOrDefault(value)

    private fun videoFromUrl(url: String, referer: String, videoHeaders: Headers): List<Video> {
        val absoluteUrl = if (url.startsWith("http")) url else url.absoluteUrl()
        return if (absoluteUrl.contains(".m3u8", ignoreCase = true)) {
            playlistUtils.extractFromHls(
                playlistUrl = absoluteUrl,
                referer = referer,
                masterHeaders = videoHeaders,
                videoHeaders = videoHeaders,
                videoNameGen = { quality -> "Hentaizm - $quality" },
            )
        } else {
            listOf(
                Video(
                    url = absoluteUrl,
                    quality = "Hentaizm - Otomatik",
                    videoUrl = absoluteUrl,
                    headers = videoHeaders,
                ),
            )
        }
    }

    private fun labelValue(document: Document, label: String): String? {
        val paragraph = document.select("p:has(strong)").firstOrNull { element ->
            element.selectFirst("strong")?.text()?.trim()?.trimEnd(':')?.equals(label, ignoreCase = true) == true
        } ?: return null

        return paragraph.clone().apply { select("strong").remove() }.text().trim()
            .takeIf(String::isNotBlank)
    }

    private fun summary(document: Document): String? {
        val heading = document.select("h1, h2, h3, h4").firstOrNull {
            it.text().contains("Özet", ignoreCase = true)
        }
        return heading?.nextElementSibling()?.text()?.trim()
            ?.takeUnless { it.contains("giriş", ignoreCase = true) }
            ?.takeIf(String::isNotBlank)
    }

    private fun posterFromAnimeUrl(url: String): String {
        val slug = url.substringAfter("/anime/").substringBefore('?').trim('/')
        return if (slug.isBlank()) "" else "$baseUrl/uploads/afis/$slug.jpg"
    }

    private fun String.absoluteUrl(): String = if (startsWith("http")) this else "$baseUrl$this"

    private fun relativeUrl(url: String): String = url.removePrefix(baseUrl).ifBlank { "/" }

    private fun formatNumber(value: Float): String = if (value % 1F == 0F) {
        value.toInt().toString()
    } else {
        value.toString()
    }

    companion object {
        private data class PlayerAlternative(
            val name: String,
            val provider: String,
            val videoId: String,
            val embedUrl: String,
        )

        private val PROVIDERS_REGEX = Regex("__TA_PROVIDERS_ENC\\s*=\\s*['\"]([^'\"]+)")
        private val EPISODE_TITLE_SUFFIX = Regex("\\s+\\d+(?:[.,]\\d+)?\\.\\s*Bölüm.*$", RegexOption.IGNORE_CASE)
        private val EPISODE_URL = Regex("/sezon-(\\d+)-bolum-([\\d.,]+)")
    }
}
