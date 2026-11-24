package com.nuvio.app.features.player

import com.nuvio.app.features.addons.AddonManifest
import com.nuvio.app.features.addons.AddonResource
import com.nuvio.app.features.addons.ManagedAddon
import com.nuvio.app.features.streams.StreamParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SourceSubtitleLookupTest {
    @Test
    fun sendsExactAndCanonicalEpisodeIdsToCompatibleAddonsWithAndWithoutFileHints() {
        val lookup = SubtitleLookup("tv", "tmdb:123:0:2", "tmdb:123", "tt1234", extraPath = "filename=release.mkv")
        val addons = listOf(addon("tt", "tv"), addon("tmdb:", "series"))
        val requests = addonSubtitleRequests(lookup, addons)
        assertNotNull(buildAddonSubtitleFetchKey(addons, lookup))
        assertEquals(listOf(
            "https://example.test/subtitles/series/tt1234%3A0%3A2/filename=release.mkv.json?config=1",
            "https://example.test/subtitles/series/tmdb%3A123%3A0%3A2/filename=release.mkv.json?config=1",
        ), requests.map { it.url })
        assertEquals(listOf(
            "https://example.test/subtitles/series/tt1234%3A0%3A2.json?config=1",
            "https://example.test/subtitles/series/tmdb%3A123%3A0%3A2.json?config=1",
        ), requests.map { it.fallbackUrl })
    }

    @Test
    fun doesNotSubstituteTheSeriesIdForAnUnknownEpisode() {
        assertTrue(SubtitleLookup("series", "tt1234", "tt1234").videoIds().isEmpty())
        assertEquals(listOf("opaque", "tt1234:2:3"), SubtitleLookup(
            "series", "opaque", imdbId = "tt1234", season = 2, episode = 3,
        ).videoIds())
        assertEquals(listOf("tmdb:123", "tt1234"), SubtitleLookup(
            "movie", "tmdb:123", imdbId = "tt1234",
        ).videoIds())
    }

    @Test
    fun parsesSourceSidecarsAndEncodesReleaseMetadata() {
        val stream = StreamParser.parse(
            """{"streams":[{"url":"https://media.test/video",
                "behaviorHints":{"videoHash":"abc123","videoSize":420,"filename":"A & B.mkv"},
                "subtitles":[null,{"lang":"eng"},{"url":"file:///private.srt"},
                {"url":"https://media.test/en.srt","lang":"eng","label":"English",
                "behaviorHints":{"proxyHeaders":{"request":{"Referer":"https://media.test/"}}}}]}]}""",
            addonName = "Fixture",
            addonId = "fixture",
        ).single()
        val sidecar = stream.externalSubtitles.single()
        assertEquals("https://media.test/en.srt", sidecar.url)
        assertEquals("eng", sidecar.language)
        assertEquals("English", sidecar.name)
        assertEquals(mapOf("Referer" to "https://media.test/"), sidecar.headers)
        assertEquals("videoHash=abc123&videoSize=420&filename=A%20%26%20B.mkv", stream.subtitleRequestExtra())
    }

    private fun addon(prefix: String, type: String): ManagedAddon {
        val url = "https://example.test/manifest.json?config=1"
        return ManagedAddon(url, AddonManifest(
            id = prefix, name = prefix, description = "", version = "1",
            resources = listOf(AddonResource("subtitles", listOf(type), listOf(prefix))),
            types = listOf(type), transportUrl = url,
        ))
    }
}
