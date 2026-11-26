package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.Lyrics
import dev.brahmkshatriya.echo.common.models.Track
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class LiveApiTest {

    @Test
    fun readsThePublishedCatalogueAndLoadsASong() = runBlocking {
        assumeTrue(System.getProperty("echo.live") == "1")
        val base = "https://2755337087.github.io/ttml-hub/"
        val body = Http.get(base + "api/v1/songs.json")
        val songs = Http.json(body).obj()?.arr("songs")?.objects().orEmpty()
        assertTrue("catalogue is empty", songs.size > 100)
        val row = songs.firstOrNull {
            !it.str("title").isNullOrBlank() && it.arr("artists").strings().isNotEmpty()
        }
        assertNotNull("no usable catalogue entry", row)
        val entry = row!!
        val artists = entry.arr("artists").strings()
        val track = Track(
            id = "live-probe",
            title = entry.str("title")!!,
            artists = listOf(Artist("1", artists.first())),
            album = null,
            duration = 0L,
        )
        val source = LunaBeatSource()
        val candidates = source.search(track, null)
        assertTrue("a catalogue entry did not match itself", candidates.isNotEmpty())
        val loaded = source.load(candidates.first(), null)
        val lyric = loaded.lyrics
        assertNotNull(lyric)
        if (lyric is Lyrics.Timed) assertTrue(lyric.list.isNotEmpty())
        if (lyric is Lyrics.WordByWord) assertTrue(lyric.list.isNotEmpty())
        println("live: catalogue=${songs.size} title=${track.title} -> ${lyric?.javaClass?.simpleName}")
    }
}
