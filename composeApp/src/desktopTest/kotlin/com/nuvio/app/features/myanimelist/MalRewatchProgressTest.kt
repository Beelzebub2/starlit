package com.nuvio.app.features.myanimelist

import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaVideo
import org.junit.Test
import kotlin.test.*

class MalRewatchProgressTest {
    private fun meta(vararg seasons: Pair<Int, Int>) = MetaDetails(
        id = "fixture:series", type = "series", name = "Fixture",
        videos = seasons.map { (season, episode) ->
            MetaVideo(id = "s${season}e$episode", title = "Episode", season = season, episode = episode)
        },
    )

    @Test fun completeSingleSeasonIsCountedOnlyOnceAfterEveryEpisode() {
        val title = meta(1 to 1, 1 to 2, 1 to 3)
        assertEquals(2, matchedMalRewatchEpisode(title, 3, season = 1, episode = 2))
        var progress = MalRewatchProgress(baseline = 4)
        progress = progress.record(2).record(2)
        assertFalse(progress.complete(3))
        progress = progress.record(1).record(3)
        assertTrue(progress.complete(3))
        assertEquals(4, progress.baseline)
        assertEquals(setOf(1, 2, 3), progress.completedEpisodes)
    }

    @Test fun aMovieCompletesOnlyWithItsSingleMalEpisode() {
        val movie = MetaDetails(id = "fixture:movie", type = "movie", name = "Movie")
        assertEquals(1, matchedMalRewatchEpisode(movie, 1, null, null))
        assertNull(matchedMalRewatchEpisode(movie, 2, null, null))
        assertNull(matchedMalRewatchEpisode(movie, 1, 1, 1))
    }

    @Test fun ambiguousIncompleteOrMultipleSeasonListsCannotAutoIncrement() {
        val title = meta(1 to 1, 1 to 2, 2 to 1, 2 to 2)
        assertNull(matchedMalRewatchEpisode(title, 2, 1, 1))
        assertNull(matchedMalRewatchEpisode(meta(1 to 1, 1 to 3), 3, 1, 1))
        assertNull(matchedMalRewatchEpisode(meta(1 to 1, 1 to 2), 0, 1, 1))
        assertNull(matchedMalRewatchEpisode(meta(1 to 1, 1 to 2), 2, 1, 3))
        assertNull(matchedMalRewatchEpisode(meta(1 to 1, 1 to 2), 2, null, 1))
    }
}
