package eu.kanade.tachiyomi.animeextension.tr.animpow

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.injectLazy

class Animpow : AnimeHttpSource() {

    override val name = "Animpow"

    override val baseUrl = "https://animpow.com"

    override val lang = "tr"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    private val sourceHeaders = headers.newBuilder()
        .set("Referer", "$baseUrl/")
        .set("Origin", baseUrl)
        .build()

    private val videoHeaders: Headers = sourceHeaders.newBuilder()
        .set("Accept", "*/*")
        .build()

    private val api by lazy { AnimpowApiClient(client, json, sourceHeaders) }

    private val webViewResolver by lazy { AnimpowWebViewResolver(sourceHeaders) }

    override fun popularAnimeRequest(page: Int): Request = throw UnsupportedOperationException()

    override fun popularAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    override suspend fun getPopularAnime(page: Int): AnimesPage = catalog(page, "popularity")

    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()

    override fun latestUpdatesParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    override suspend fun getLatestUpdates(page: Int): AnimesPage = catalog(page, "year")

    private suspend fun catalog(page: Int, sort: String): AnimesPage {
        val root = api.get(
            "/anime/list",
            "sayfa" to page.toString(),
            "limit" to PAGE_SIZE.toString(),
            "siralama_alani" to sort,
            "siralama_duzu" to "desc",
        )

        val anime = root.array("veri")
            .mapNotNull { it.objOrNull()?.toAnime() }
            .distinctBy { it.url }

        val meta = root.objOrNull("meta")
        val currentPage = meta?.int("sayfa") ?: meta?.int("mevcut_sayfa") ?: page
        val lastPage = meta?.int("toplam_sayfa") ?: meta?.int("last_page") ?: currentPage

        return AnimesPage(anime, currentPage < lastPage)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = throw UnsupportedOperationException()

    override fun searchAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        if (query.isBlank()) return getPopularAnime(page)

        val root = api.get(
            "/anime/arama",
            "q" to query,
            "limit" to PAGE_SIZE.toString(),
            "sayfa" to page.toString(),
        )

        val anime = root.array("veri")
            .mapNotNull { it.objOrNull()?.toAnime() }
            .distinctBy { it.url }

        val meta = root.objOrNull("meta")
        val currentPage = meta?.int("sayfa") ?: meta?.int("mevcut_sayfa") ?: page
        val lastPage = meta?.int("toplam_sayfa") ?: meta?.int("last_page") ?: currentPage
        val hasNextPage = if (lastPage > currentPage) true else anime.size >= PAGE_SIZE && meta != null

        return AnimesPage(anime, hasNextPage)
    }

    private fun JsonObject.toAnime(): SAnime? {
        val id = int("animpow_core_id") ?: int("id") ?: return null
        val displayTitle = string("name_english")
            ?: string("name")
            ?: string("title_name")
            ?: return null

        return SAnime.create().apply {
            title = displayTitle
            url = "/anime/$id"
            thumbnail_url = string("poster").orEmpty()
            description = string("description")
            genre = genresToString(array("genres"))
            status = statusFromText(string("jikan_status").orEmpty())
        }
    }

    override fun animeDetailsRequest(anime: SAnime): Request = throw UnsupportedOperationException()

    override fun animeDetailsParse(response: Response): SAnime = throw UnsupportedOperationException()

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val root = api.get("/anime/${anime.animpowId()}")
        val item = root.objOrNull("anime") ?: root

        return anime.apply {
            title = item.string("name_english") ?: item.string("name") ?: item.string("title_name") ?: title
            thumbnail_url = item.string("poster") ?: thumbnail_url
            description = item.string("description")
            genre = genresToString(item.array("genres"))
            status = statusFromText(item.string("jikan_status").orEmpty())
            initialized = true
        }
    }

    override fun episodeListRequest(anime: SAnime): Request = throw UnsupportedOperationException()

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val animeId = anime.animpowId()
        val episodeRecords = api.get("/anime/$animeId/bolumler").array("episodes")
        val episodes = episodeRecords
            .mapNotNull { it.objOrNull() }
            .filter { it.float("episode_num") != null }
            .groupBy { (it.int("season_num") ?: 1) to (it.float("episode_num") ?: 0F) }
            .values
            .filter { it.isNotEmpty() }
            .mapNotNull { records ->
                val primary = records.sortedBy(::sourcePriority).firstOrNull() ?: return@mapNotNull null
                val season = primary.int("season_num") ?: 1
                val episodeNumber = primary.float("episode_num") ?: return@mapNotNull null
                primary.toEpisode(animeId, season, episodeNumber)
            }

        if (episodes.isNotEmpty()) {
            return episodes.sortedWith(
                compareByDescending<SEpisode> { EPISODE_REGEX.find(it.url)?.groupValues?.get(1)?.toIntOrNull() ?: 0 }
                    .thenByDescending { it.episode_number },
            )
        }

        return generatedEpisodesFromDetails(animeId)
    }

    private fun JsonObject.toEpisode(animeId: Int, season: Int, episodeNumber: Float): SEpisode {
        val episodeTitle = string("episode_name")?.takeIf(String::isNotBlank)
        return SEpisode.create().apply {
            name = buildString {
                append("Sezon ")
                append(season)
                append(" - Bolum ")
                append(formatNumber(episodeNumber))
                episodeTitle?.let {
                    append(" - ")
                    append(it)
                }
            }
            url = "/watch/$animeId/s${season}e${formatNumber(episodeNumber)}"
            episode_number = episodeNumber
        }
    }

    private suspend fun generatedEpisodesFromDetails(animeId: Int): List<SEpisode> {
        val detail = api.get("/anime/$animeId").objOrNull("anime") ?: return emptyList()
        val totalEpisodes = detail.int("total_episodes") ?: 0
        val titleType = detail.string("title_type").orEmpty()

        if (titleType.equals("movie", ignoreCase = true) || totalEpisodes <= 1) {
            return listOf(
                SEpisode.create().apply {
                    name = "Film"
                    url = "/watch/$animeId/s1e1"
                    episode_number = 1F
                },
            )
        }

        val generated = mutableListOf<SEpisode>()
        val seasons = detail.array("seasons_data").mapNotNull { it.objOrNull() }
        if (seasons.isNotEmpty()) {
            seasons.forEach { season ->
                val seasonNumber = season.int("number") ?: season.int("season_num") ?: 1
                val episodeCount = season.int("episode_count") ?: return@forEach
                for (episode in 1..episodeCount) {
                    generated += SEpisode.create().apply {
                        name = "Sezon $seasonNumber - Bolum $episode"
                        url = "/watch/$animeId/s${seasonNumber}e$episode"
                        episode_number = episode.toFloat()
                    }
                }
            }
        } else {
            for (episode in 1..totalEpisodes) {
                generated += SEpisode.create().apply {
                    name = "Bolum $episode"
                    url = "/watch/$animeId/s1e$episode"
                    episode_number = episode.toFloat()
                }
            }
        }

        return generated.sortedWith(
            compareByDescending<SEpisode> { EPISODE_REGEX.find(it.url)?.groupValues?.get(1)?.toIntOrNull() ?: 0 }
                .thenByDescending { it.episode_number },
        )
    }

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val match = EPISODE_REGEX.find(episode.url)
            ?: throw Exception("Animpow bolum URL'i okunamadi.")
        val animeId = match.groupValues[1].toInt()
        val season = match.groupValues[2].toInt()
        val episodeNumber = match.groupValues[3].toFloat()

        val records = api.get("/anime/$animeId/bolumler")
            .array("episodes")
            .mapNotNull { it.objOrNull() }
            .filter {
                (it.int("season_num") ?: 1) == season &&
                    (it.float("episode_num") ?: -1F) == episodeNumber
            }
            .sortedBy(::sourcePriority)

        val directVideos = records
            .flatMap(::videosFromRecord)
            .distinctBy { it.videoUrl }
            .sortedByDescending { qualityNumber(it.quality) }

        if (directVideos.isNotEmpty()) return directVideos

        val result = webViewResolver.resolve(episode.url.absoluteUrl())
        result.error?.let { throw Exception(it) }
        val streamUrl = result.url
            ?: throw Exception("Animpow video baglantisi alinamadi. Lutfen tekrar deneyin.")

        val fallbackHeaders = videoHeaders.newBuilder().apply {
            result.cookie?.takeIf(String::isNotBlank)?.let { set("Cookie", it) }
        }.build()

        return listOf(
            Video(
                url = streamUrl,
                quality = "Animpow CDN${result.quality?.let { " - $it" }.orEmpty()}",
                videoUrl = streamUrl,
                headers = fallbackHeaders,
            ),
        )
    }

    private fun videosFromRecord(record: JsonObject): List<Video> {
        val sourceName = record.string("fansub_name")
            ?: record.string("video_source_name")
            ?: "Animpow"

        val videos = mutableListOf<Video>()

        record.array("pro_cdn_data").mapNotNull { it.objOrNull() }.forEach { source ->
            val link = source.string("link") ?: return@forEach
            val quality = source.string("quality")
                ?: source.string("format")
                ?: qualityFromUrl(link)
                ?: "Video"
            videos += video(link, "Pro CDN - $quality - $sourceName")
        }

        record.string("cdn_m3u8")?.takeIf(String::isNotBlank)?.let {
            videos += video(it, "Animpow CDN - HLS - $sourceName")
        }
        record.string("cdn_mp4_1080")?.takeIf(String::isNotBlank)?.let {
            videos += video(it, "Animpow CDN - 1080p - $sourceName")
        }
        record.string("cdn_mp4_720")?.takeIf(String::isNotBlank)?.let {
            videos += video(it, "Animpow CDN - 720p - $sourceName")
        }
        record.string("cdn_mp4_480")?.takeIf(String::isNotBlank)?.let {
            videos += video(it, "Animpow CDN - 480p - $sourceName")
        }

        record.string("url")
            ?.takeIf { it.endsWith(".mp4", ignoreCase = true) || it.contains(".m3u8", ignoreCase = true) }
            ?.let { videos += video(it, "${qualityFromUrl(it) ?: "Video"} - $sourceName") }

        return videos
    }

    private fun video(url: String, quality: String): Video = Video(url, quality, url, headers = videoHeaders)

    private fun sourcePriority(record: JsonObject): Int = when {
        record.boolean("pro_cdn_active") && record.array("pro_cdn_data").isNotEmpty() -> 0
        record.boolean("animpow_cdn_v1_active") || record.string("video_source_name") == "AnimPow Cdn 1" -> 1
        record.string("video_source_name")?.contains("sibnet", ignoreCase = true) == true -> 2
        record.string("video_source_name")?.contains("uqload", ignoreCase = true) == true -> 3
        else -> 4
    }

    private fun genresToString(items: JsonArray): String = items.mapNotNull { item ->
        when {
            item is JsonObject -> item.string("name") ?: item.string("title")
            else -> item.jsonPrimitiveOrNull()?.contentOrNull
        }
    }.joinToString()

    private fun SAnime.animpowId(): Int = url.substringAfter("/anime/").substringBefore("/").toInt()

    private fun String.absoluteUrl(): String = if (startsWith("http")) this else "$baseUrl$this"

    private fun statusFromText(text: String): Int = when {
        text.contains("finished", ignoreCase = true) || text.contains("tamam", ignoreCase = true) -> SAnime.COMPLETED
        text.contains("currently", ignoreCase = true) || text.contains("airing", ignoreCase = true) || text.contains("yay", ignoreCase = true) -> SAnime.ONGOING
        else -> SAnime.UNKNOWN
    }

    private fun JsonElement.objOrNull(): JsonObject? = this as? JsonObject

    private fun JsonElement.jsonPrimitiveOrNull() = runCatching { jsonPrimitive }.getOrNull()

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitiveOrNull()?.contentOrNull

    private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitiveOrNull()?.intOrNull

    private fun JsonObject.float(key: String): Float? = this[key]?.jsonPrimitiveOrNull()?.floatOrNull

    private fun JsonObject.boolean(key: String): Boolean {
        val primitive = this[key]?.jsonPrimitiveOrNull() ?: return false
        return primitive.booleanOrNull ?: primitive.intOrNull?.let { it != 0 } ?: false
    }

    private fun JsonObject.array(key: String): JsonArray = this[key] as? JsonArray ?: JsonArray(emptyList())

    private fun JsonObject.objOrNull(key: String): JsonObject? = this[key] as? JsonObject

    private fun formatNumber(value: Float): String = if (value % 1F == 0F) value.toInt().toString() else value.toString()

    private fun qualityNumber(label: String): Int = Regex("(\\d{3,4})p").find(label)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    private fun qualityFromUrl(url: String): String? = Regex("(1080|720|480|360)p?").find(url)?.groupValues?.get(1)?.let { "${it}p" }

    companion object {
        private const val PAGE_SIZE = 30
        private val EPISODE_REGEX = Regex("/watch/(\\d+)/s(\\d+)e([\\d.]+)")
    }
}
