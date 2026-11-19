package com.nuvio.app.features.lists

import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.myanimelist.MalAnime
import com.nuvio.app.features.myanimelist.MalAnimeListEntry
import com.nuvio.app.features.myanimelist.MalListPatch
import com.nuvio.app.features.myanimelist.MalListStatus
import com.nuvio.app.features.myanimelist.MalStatus
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MalListServiceTest {
    @Test fun statusMappingRoundTrips() {
        MalStatus.entries.forEach { assertEquals(it, it.toListStatus().toMalStatus()) }
        ListStatus.entries.forEach { assertEquals(it, it.toMalStatus().toListStatus()) }
    }

    @Test fun entryMappingCarriesRewatchAndEpisodes() {
        val entry = MalAnimeListEntry(
            anime = MalAnime(id = 1535, title = "Death Note"),
            imageUrl = "https://img/p.jpg",
            totalEpisodes = 12,
            listStatus = MalListStatus(MalStatus.WATCHING, score = 8, watchedEpisodes = 7, isRewatching = true, numTimesRewatched = 2),
            airing = true,
        ).toListEntry()
        assertEquals(ListEntryKey(ListServiceId.MYANIMELIST, "1535"), entry.key)
        assertEquals("https://myanimelist.net/anime/1535", entry.siteUrl)
        assertEquals(2, entry.rewatchCount)
        assertTrue(entry.rewatching)
        assertEquals(7, entry.watchedEpisodes)
        assertEquals(12, entry.totalEpisodes)
        assertTrue(entry.airing)
        assertEquals(ListStatus.WATCHING, entry.status)
        assertEquals(8, entry.score)
    }

    @Test fun coversOnlyAnime() {
        val meta = MetaDetails(id = "x", type = "series", name = "X")
        assertTrue(MalListService.covers(ListTitleKind(ListMediaType.SERIES, anime = true), meta))
        assertFalse(MalListService.covers(ListTitleKind(ListMediaType.SERIES, anime = false), meta))
    }

    @Test fun capabilitiesAndSorts() {
        val caps = MalListService.capabilities(ListMediaType.SERIES)
        assertEquals(ListStatus.entries.toList(), caps.statuses)
        assertFalse(caps.removeFromHistory)
        assertTrue(caps.leavableStatuses.isEmpty())
        assertEquals("UPDATED", MalListService.sorts.first().key)
    }

    @Test fun stepPatchClampsToTotalAndZero() {
        val base = MalAnimeListEntry(MalAnime(1, "A"), null, 12, MalListStatus(MalStatus.WATCHING, 0, 11)).toListEntry()
        assertEquals(12, malStepPatch(base, 5).watchedEpisodes)
        assertEquals(0, malStepPatch(base, -50).watchedEpisodes)
    }

    @Test fun stepsAndStatusFollowMyAnimeListRules() {
        val planned = MalAnimeListEntry(MalAnime(1, "A"), null, 12, MalListStatus(MalStatus.PLAN_TO_WATCH, 0, 0)).toListEntry()
        assertEquals(MalListPatch(status = MalStatus.WATCHING, watchedEpisodes = 1), malStepPatch(planned, 1))
        val nearlyDone = MalAnimeListEntry(MalAnime(1, "A"), null, 12, MalListStatus(MalStatus.WATCHING, 0, 11)).toListEntry()
        assertEquals(MalListPatch(status = MalStatus.COMPLETED, watchedEpisodes = 12), malStepPatch(nearlyDone, 1))
        assertEquals(MalListPatch(status = MalStatus.COMPLETED, watchedEpisodes = 12), malStatusPatch(nearlyDone, ListStatus.COMPLETED))
        assertEquals(MalListPatch(status = MalStatus.ON_HOLD), malStatusPatch(nearlyDone, ListStatus.ON_HOLD))
        assertEquals(null, malStatusPatch(nearlyDone, ListStatus.WATCHING))
    }
}
