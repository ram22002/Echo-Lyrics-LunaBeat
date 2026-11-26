package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.models.Lyrics
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.settings.Setting
import dev.brahmkshatriya.echo.common.settings.SettingTextInput
import dev.brahmkshatriya.echo.common.settings.Settings

/**
 * LunaBeat publishes a static TTML catalogue (songs.json + per-song files) on GitHub Pages.
 * The index is ~1.7 MB, so it is fetched once per process and cached.
 */
internal class LunaBeatSource : LyricsSource {

    private val throttle = Throttle(200)

    override suspend fun search(track: Track, settings: Settings?): List<Lyrics> {
        val query = track.query()
        if (query.title.isBlank()) return emptyList()
        val base = baseUrl(settings)
        val catalogue = index(base) ?: return emptyList()
        val normalizedTitle = normalize(track.title)
        val normalizedArtist = normalize(query.artist)

        return catalogue.asSequence()
            .map { song ->
                val titleHit = normalize(song.title) == normalizedTitle ||
                    normalize(song.title).contains(normalizedTitle) ||
                    normalizedTitle.contains(normalize(song.title))
                val artistHit = song.artists.any { normalize(it) == normalizedArtist } ||
                    song.artists.any { normalize(it).contains(normalizedArtist) }
                song to (titleHit to artistHit)
            }
            .filter { (song, hit) -> hit.first && (hit.second || song.artists.isEmpty()) }
            .take(5)
            .map { (song, _) ->
                Lyrics(
                    id = "lunabeat:${song.id}",
                    title = song.title,
                    subtitle = song.artists.joinToString(", ") + " • LunaBeat",
                    lyrics = null,
                    extras = mapOf("path" to song.path, "base" to base),
                )
            }
            .toList()
    }

    override suspend fun load(lyrics: Lyrics, settings: Settings?): Lyrics {
        val path = lyrics.extras["path"] ?: throw LyricsException("LunaBeat candidate is missing its file path")
        val base = lyrics.extras["base"] ?: baseUrl(settings)
        throttle.await()
        val ttml = Http.get(base + path)
        val lines = Ttml.parse(ttml)
        if (lines.isEmpty()) throw LyricsException("LunaBeat TTML could not be parsed")
        return lyrics.copy(lyrics = timed(lines, wordLevel = lines.any { it.words.size > 1 }))
    }

    private fun baseUrl(settings: Settings?): String {
        val override = settings?.getString(KEY_ENDPOINT)?.trim().orEmpty()
        val base = override.ifEmpty { BASE_URL }
        return base.trimEnd('/') + "/"
    }

    private suspend fun index(base: String): List<Song>? {
        val cached = cache
        if (cached != null && cached.first == base) return cached.second
        throttle.await()
        val body = runCatching { Http.get(base + "api/v1/songs.json") }.getOrNull() ?: return null
        val songs = runCatching {
            Http.json(body).obj()?.arr("songs").objects().mapNotNull { item ->
                val id = item.str("id") ?: return@mapNotNull null
                val title = item.str("title") ?: return@mapNotNull null
                val path = item.str("path") ?: return@mapNotNull null
                val artists = item.arr("artists").strings()
                val album = item.str("album").orEmpty()
                Song(id, title, artists, album, path)
            }
        }.getOrNull() ?: return null
        cache = base to songs
        return songs
    }

    private fun normalize(value: String): String = Match.normalize(value)

    private data class Song(val id: String, val title: String, val artists: List<String>, val album: String, val path: String)

    companion object {
        const val KEY_ENDPOINT = "endpoint"
        private const val BASE_URL = "https://2755337087.github.io/ttml-hub/"

        @Volatile
        private var cache: Pair<String, List<Song>>? = null

        fun settings(): List<Setting> = listOf(
            SettingTextInput(
                title = "Catalogue base URL",
                key = KEY_ENDPOINT,
                summary = "Leave blank for the public LunaBeat hub.",
                defaultValue = "",
            ),
        )
    }
}
