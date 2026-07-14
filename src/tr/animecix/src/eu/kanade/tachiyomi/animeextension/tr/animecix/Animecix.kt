package eu.kanade.tachiyomi.animeextension.tr.animecix

import android.util.Base64
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.injectLazy
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class Animecix : AnimeHttpSource() {

    override val name = "AnimeciX"

    override val baseUrl = "https://animecix.tv"

    override val lang = "tr"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    private var translatorCache: Map<Int, String>? = null

    override val client = network.client.newBuilder()
        .addInterceptor { chain ->
            val request = chain.request()
            if (request.method == "GET" && request.url.host == "animecix.tv") {
                val signed = request.newBuilder()
                    .header("Accept", "application/json, text/plain, */*")
                    .header("Referer", "$baseUrl/")
                    .header(SIGNATURE_HEADER, createSignature(request.url.encodedQuery.orEmpty()))
                    .build()
                chain.proceed(signed)
            } else {
                chain.proceed(request)
            }
        }
        .build()

    // Popular and latest are derived from AnimeciX's public last-episodes feed.
    override fun popularAnimeRequest(page: Int): Request = lastEpisodesRequest(page)

    override fun popularAnimeParse(response: Response): AnimesPage = lastEpisodesParse(response)

    override fun latestUpdatesRequest(page: Int): Request = lastEpisodesRequest(page)

    override fun latestUpdatesParse(response: Response): AnimesPage = lastEpisodesParse(response)

    private fun lastEpisodesRequest(page: Int): Request {
        val url = "$baseUrl/secure/last-episodes".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url, headers)
    }

    private fun lastEpisodesParse(response: Response): AnimesPage {
        val root = response.body.string().asJsonObject()
        val animeById = linkedMapOf<Int, SAnime>()

        root.array("data").forEach { itemElement ->
            val item = itemElement.jsonObject
            val titleId = item.int("title_id") ?: return@forEach
            animeById.getOrPut(titleId) {
                SAnime.create().apply {
                    title = item.string("title_name") ?: "AnimeciX #$titleId"
                    thumbnail_url = item.string("title_poster").orEmpty()
                    url = "/titles/$titleId"
                }
            }
        }

        val currentPage = root.int("current_page") ?: 1
        val lastPage = root.int("last_page") ?: currentPage
        return AnimesPage(animeById.values.toList(), currentPage < lastPage)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.isBlank()) return popularAnimeRequest(page)

        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("secure/search")
            .addPathSegment(query)
            .addQueryParameter("type", "title")
            .addQueryParameter("provider", "local")
            .build()
        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        if (response.request.url.encodedPath.contains("last-episodes")) {
            return lastEpisodesParse(response)
        }

        val root = response.body.string().asJsonObject()
        val results = root.array("results").mapNotNull(::animeFromSearchResult)
        return AnimesPage(results, false)
    }

    private fun animeFromSearchResult(element: JsonElement): SAnime? {
        val item = element.jsonObject
        val id = item.int("id") ?: return null
        return SAnime.create().apply {
            title = item.string("name") ?: return null
            thumbnail_url = item.string("poster").orEmpty()
            description = item.string("description")
            genre = item.array("genres").mapNotNull { it.jsonObject.string("name") }.joinToString()
            status = item.toStatus()
            url = "/titles/$id"
        }
    }

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val titleId = anime.titleId()
        val root = executeJson(GET("$baseUrl/secure/titles/$titleId", headers))
        val title = root.obj("title")

        return anime.apply {
            this.title = title.string("name") ?: this.title
            description = title.string("description")
            thumbnail_url = title.string("poster") ?: thumbnail_url
            genre = title.array("genres").mapNotNull { it.jsonObject.string("name") }.joinToString()
            status = title.toStatus()
            initialized = true
        }
    }

    override fun animeDetailsParse(response: Response): SAnime = throw UnsupportedOperationException("Coroutine implementation is used")

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val titleId = anime.titleId()
        val baseTitle = executeJson(GET("$baseUrl/secure/titles/$titleId", headers)).obj("title")
        val seasons = baseTitle.array("seasons")
            .mapNotNull { it.jsonObject.int("number") }
            .distinct()
            .sorted()
            .ifEmpty { listOf(1) }

        val episodes = mutableListOf<SEpisode>()

        seasons.forEach { seasonNumber ->
            var page = 1
            while (true) {
                val url = "$baseUrl/secure/titles/$titleId".toHttpUrl().newBuilder()
                    .addQueryParameter("seasonNumber", seasonNumber.toString())
                    .addQueryParameter("page", page.toString())
                    .build()

                val title = executeJson(GET(url, headers)).obj("title")
                val pagination = title.objOrNull("season")?.objOrNull("episodePagination") ?: break
                val pageEpisodes = pagination.array("data")

                pageEpisodes.forEach { episodeElement ->
                    val episode = episodeElement.jsonObject
                    val episodeNumber = episode.float("episode_number") ?: return@forEach
                    val actualSeason = episode.int("season_number") ?: seasonNumber
                    episodes += SEpisode.create().apply {
                        name = buildString {
                            append(actualSeason)
                            append(". Sezon ")
                            append(formatEpisodeNumber(episodeNumber))
                            append(". Bölüm")
                            episode.string("name")?.takeIf(String::isNotBlank)?.let {
                                append(" — ")
                                append(it)
                            }
                        }
                        this.url = "/titles/$titleId/season/$actualSeason/episode/${formatEpisodeNumber(episodeNumber)}"
                        this.episode_number = episodeNumber
                        date_upload = parseDate(episode.string("release_date"))
                    }
                }

                val lastPage = pagination.int("last_page") ?: page
                if (page >= lastPage || pageEpisodes.isEmpty()) break
                page++
            }
        }

        return episodes
            .distinctBy { it.url }
            .sortedWith(
                compareByDescending<SEpisode> { it.seasonFromUrl() }
                    .thenByDescending { it.episode_number },
            )
    }

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException("Coroutine implementation is used")

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val titleId = episode.url.substringAfter("/titles/").substringBefore("/").toInt()
        val seasonNumber = episode.seasonFromUrl()
        val episodeNumber = episode.url.substringAfterLast("/").toFloat()

        val url = "$baseUrl/secure/titles/$titleId".toHttpUrl().newBuilder()
            .addQueryParameter("seasonNumber", seasonNumber.toString())
            .build()
        val title = executeJson(GET(url, headers)).obj("title")
        val translators = getTranslators()

        return title.array("videos")
            .map { it.jsonObject }
            .filter {
                it.string("category") == "full" &&
                    it.boolean("approved", true) &&
                    it.int("season_num") == seasonNumber &&
                    it.float("episode_num") == episodeNumber
            }
            .flatMap { video ->
                val embedUrl = video.string("url") ?: return@flatMap emptyList()
                val sourceName = translators[video.int("template")]
                    ?: video.string("name")
                    ?: "AnimeciX"
                extractVideo(embedUrl, sourceName)
            }
            .distinctBy { it.videoUrl }
            .sortedByDescending { qualityNumber(it.quality) }
    }

    private suspend fun extractVideo(embedUrl: String, sourceName: String): List<Video> {
        if (embedUrl.contains("tau-video.xyz/embed/")) {
            val videoId = embedUrl.substringAfterLast("/").substringBefore("?")
            val request = GET(
                "https://tau-video.xyz/api/video/$videoId",
                headers.newBuilder().set("Referer", embedUrl).build(),
            )
            val root = executeJson(request)
            return root.array("urls").mapNotNull { sourceElement ->
                val source = sourceElement.jsonObject
                val videoUrl = source.string("url") ?: return@mapNotNull null
                val label = source.string("label") ?: "Video"
                Video(videoUrl, "$label • $sourceName", videoUrl)
            }
        }

        if (embedUrl.substringBefore("?").endsWith(".mp4") || embedUrl.contains(".m3u8")) {
            return listOf(Video(embedUrl, sourceName, embedUrl))
        }

        return emptyList()
    }

    private suspend fun getTranslators(): Map<Int, String> {
        translatorCache?.let { return it }

        val request = GET("$baseUrl/secure/translators", headers)
        val response = client.newCall(request).awaitSuccess()
        val parsed = response.use {
            json.parseToJsonElement(it.body.string()).jsonArray.associate { item ->
                val translator = item.jsonObject
                (translator.int("id") ?: -1) to (translator.string("translator") ?: "AnimeciX")
            }.filterKeys { it >= 0 }
        }
        translatorCache = parsed
        return parsed
    }

    private suspend fun executeJson(request: Request): JsonObject {
        val response = client.newCall(request).awaitSuccess()
        return response.use { it.body.string().asJsonObject() }
    }

    private fun String.asJsonObject(): JsonObject = json.parseToJsonElement(this).jsonObject

    private fun SAnime.titleId(): Int = url.substringAfter("/titles/").substringBefore("/").toInt()

    private fun SEpisode.seasonFromUrl(): Int = url.substringAfter("/season/").substringBefore("/").toInt()

    private fun JsonObject.toStatus(): Int {
        val ended = this["series_ended"]?.jsonPrimitive?.let {
            it.booleanOrNull ?: (it.intOrNull?.let { value -> value != 0 })
        }
        return if (ended == true) SAnime.COMPLETED else SAnime.ONGOING
    }

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull

    private fun JsonObject.float(key: String): Float? = this[key]?.jsonPrimitive?.floatOrNull

    private fun JsonObject.boolean(key: String, default: Boolean): Boolean = this[key]?.jsonPrimitive?.booleanOrNull ?: default

    private fun JsonObject.array(key: String): JsonArray = this[key] as? JsonArray ?: JsonArray(emptyList())

    private fun JsonObject.obj(key: String): JsonObject = this[key]?.jsonObject ?: JsonObject(emptyMap())

    private fun JsonObject.objOrNull(key: String): JsonObject? = this[key] as? JsonObject

    private fun formatEpisodeNumber(value: Float): String = if (value % 1F == 0F) value.toInt().toString() else value.toString()

    private fun qualityNumber(label: String): Int = Regex("(\\d{3,4})p").find(label)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    private fun parseDate(value: String?): Long {
        if (value.isNullOrBlank()) return 0L
        val patterns = listOf("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd")
        return patterns.firstNotNullOfOrNull { pattern ->
            runCatching {
                SimpleDateFormat(pattern, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }.parse(value)?.time
            }.getOrNull()
        } ?: 0L
    }

    private fun createSignature(query: String): String {
        val keyText = SIGNATURE_PREFIX + SIGNATURE_MIDDLE + SIGNATURE_SUFFIX
        val key = SecretKeySpec(keyText.toByteArray(Charsets.UTF_8), "AES")
        val iv = ByteArray(12).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val encrypted = cipher.doFinal(("{version}" + query).toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(encrypted, Base64.NO_WRAP) + "." +
            Base64.encodeToString(iv, Base64.NO_WRAP)
    }

    companion object {
        private const val SIGNATURE_HEADER = "X-E-H"
        private const val SIGNATURE_PREFIX = "i4C7R2fXGocdYg"
        private const val SIGNATURE_MIDDLE = "FLzCbDlsJ"
        private const val SIGNATURE_SUFFIX = "jukf8G58b"
    }
}
