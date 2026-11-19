package com.nuvio.app.features.myanimelist

import kotlin.test.*

/** MyAnimeList's list rules; the editor that writes them is covered by ListEditorTest and MalListUiTest. */
class MalListEditingTest {
    private fun entry(id: Int, status: MalStatus = MalStatus.WATCHING, score: Int = 0, episodes: Int = 0, total: Int = 12) =
        MalAnimeListEntry(MalAnime(id, "Anime $id"), null, total, MalListStatus(status, score, episodes))

    @Test fun patchOnlyContainsChangedFields() {
        val before = MalListStatus(MalStatus.WATCHING, 7, 3)
        assertNull(malListPatch(before, before))
        assertEquals(MalListPatch(score = 9), malListPatch(before, before.copy(score = 9)))
        assertEquals(
            MalListPatch(status = MalStatus.COMPLETED, watchedEpisodes = 12),
            malListPatch(before, MalListStatus(MalStatus.COMPLETED, 7, 12)),
        )
        assertEquals(MalListPatch(score = 0), malListPatch(before, before.copy(score = 0)))
        assertEquals(MalListPatch(numTimesRewatched = 3),
            malListPatch(before, before.copy(numTimesRewatched = 3)))
        assertEquals(MalListPatch(isRewatching = false, numTimesRewatched = 4),
            malListPatch(
                before.copy(status = MalStatus.COMPLETED, isRewatching = true, numTimesRewatched = 3),
                before.copy(status = MalStatus.COMPLETED, isRewatching = false, numTimesRewatched = 4),
            ))
    }

    @Test fun episodesFollowMyAnimeListConventions() {
        val planned = MalListStatus(MalStatus.PLAN_TO_WATCH, 0, 0)
        assertEquals(MalListStatus(MalStatus.WATCHING, 0, 1), planned.withWatchedEpisodes(1, 12))

        val watching = MalListStatus(MalStatus.WATCHING, 8, 11)
        assertEquals(MalListStatus(MalStatus.COMPLETED, 8, 12), watching.withWatchedEpisodes(12, 12))
        // Clamped to the episode count, and never below zero.
        assertEquals(12, watching.withWatchedEpisodes(40, 12).watchedEpisodes)
        assertEquals(0, watching.withWatchedEpisodes(-3, 12).watchedEpisodes)
        // Unknown totals (still airing) never auto-complete.
        assertEquals(MalListStatus(MalStatus.WATCHING, 8, 500), watching.withWatchedEpisodes(500, 0))

        // Finishing a rewatch ends it; lowering progress on a completed title leaves it completed.
        val rewatch = MalListStatus(MalStatus.COMPLETED, 10, 11, isRewatching = true)
        assertEquals(MalListStatus(MalStatus.COMPLETED, 10, 12, isRewatching = false, numTimesRewatched = 1), rewatch.withWatchedEpisodes(12, 12))
        val done = MalListStatus(MalStatus.COMPLETED, 10, 12)
        assertEquals(MalListStatus(MalStatus.COMPLETED, 10, 11), done.withWatchedEpisodes(11, 12))
        // Dropped titles keep their status while progress changes.
        assertEquals(MalStatus.DROPPED, MalListStatus(MalStatus.DROPPED, 0, 2).withWatchedEpisodes(3, 12).status)
    }

    @Test fun finishCountsExactlyOnceAndCancellingDoesNotCount() {
        val started = MalListStatus(MalStatus.COMPLETED, 9, 12, isRewatching = true, numTimesRewatched = 2)
        val finished = started.withCompletedRewatch()
        assertEquals(3, finished.numTimesRewatched)
        assertFalse(finished.isRewatching)
        assertEquals(finished, finished.withCompletedRewatch())
        assertEquals(2, started.copy(isRewatching = false).numTimesRewatched)
        assertEquals(started.copy(isRewatching = false), started.copy(isRewatching = false).withCompletedRewatch())
        assertEquals(started.copy(numTimesRewatched = MAX_MAL_REWATCHES),
            started.copy(numTimesRewatched = MAX_MAL_REWATCHES).withCompletedRewatch())
    }

    @Test fun completingFillsEpisodesAndOtherStatusesKeepProgress() {
        val watching = MalListStatus(MalStatus.WATCHING, 6, 4)
        assertEquals(MalListStatus(MalStatus.COMPLETED, 6, 24), watching.withStatus(MalStatus.COMPLETED, 24))
        assertEquals(MalListStatus(MalStatus.COMPLETED, 6, 4), watching.withStatus(MalStatus.COMPLETED, 0))
        assertEquals(MalListStatus(MalStatus.ON_HOLD, 6, 4), watching.withStatus(MalStatus.ON_HOLD, 24))
        val rewatch = MalListStatus(MalStatus.COMPLETED, 9, 3, isRewatching = true)
        assertSame(rewatch, rewatch.withStatus(MalStatus.COMPLETED, 12))
        assertFalse(rewatch.withStatus(MalStatus.DROPPED, 12).isRewatching)
    }

    @Test fun factsDescribeTypeSeasonAndLength() {
        val entry = entry(1, total = 10).copy(mediaType = "tv", startSeason = "spring", startYear = 2016)
        assertEquals(listOf("TV", "Spring 2016", "10 eps"), malEntryFacts(entry))
        assertEquals(listOf("Movie", "2019", "1 ep"), malEntryFacts(entry(2, total = 1).copy(mediaType = "movie", startYear = 2019)))
        assertEquals(listOf("OVA"), malEntryFacts(entry(3, total = 0).copy(mediaType = "ova")))
        assertEquals("Masterpiece", malScoreLabel(10))
        assertEquals("No score", malScoreLabel(0))
    }
}
