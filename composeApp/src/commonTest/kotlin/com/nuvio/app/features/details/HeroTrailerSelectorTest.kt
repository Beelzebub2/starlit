package com.nuvio.app.features.details

import com.nuvio.app.core.build.TrailerPlaybackMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HeroTrailerSelectorTest {
    @Test
    fun directAddonMediaCanOnlyOpenExternallyAndNeverBecomesAYouTubePreview() {
        val playlistUrl = "https://cdn.example.test/sample.m3u8?signature=A%2FB"
        val direct = trailer(id = "direct", key = "sample-id", official = true).copy(directUrl = playlistUrl)
        assertNull(selectHeroTrailer(listOf(direct)))
        assertNull(direct.youtubePlaybackUrl())
        assertNull(direct.youtubeThumbnailUrl())
        for (mode in TrailerPlaybackMode.entries) {
            assertEquals(TrailerClickTarget.External(playlistUrl), direct.trailerClickTarget(mode))
        }
    }

    @Test
    fun recognizedYouTubeLinksUseOneCanonicalVideoAndKeepDistributionPlaybackMode() {
        val legacy = trailer(id = "legacy", key = "https://youtu.be/sample-id?ignored=1")
        val tmdb = trailer(id = "tmdb", key = "sample-id").copy(fromTmdb = true)
        val otherAddon = trailer(id = "addon", key = "unrelated", official = true)
        val direct = trailer(id = "media").copy(directUrl = "https://cdn.example.test/sample.mp4")
        val watch = "https://www.youtube.com/watch?v=sample-id"
        assertEquals(listOf(tmdb, direct, otherAddon), mergeMetadataTrailers(listOf(tmdb), listOf(legacy, direct, otherAddon)))
        assertEquals(tmdb, selectHeroTrailer(listOf(tmdb, otherAddon, direct)))
        assertEquals(watch, legacy.youtubePlaybackUrl())
        assertEquals("https://img.youtube.com/vi/sample-id/hqdefault.jpg", legacy.youtubeThumbnailUrl())
        assertEquals(TrailerClickTarget.External(watch), legacy.trailerClickTarget(TrailerPlaybackMode.EXTERNAL))
        assertEquals(TrailerClickTarget.InAppYouTube(watch), legacy.trailerClickTarget(TrailerPlaybackMode.IN_APP))
        val embed = trailer("embed", "https://www.youtube-nocookie.com/embed/sample-id")
        assertEquals(watch, embed.youtubePlaybackUrl())
    }

    @Test
    fun rejectedLinksCannotReachEitherPlaybackModeOrPreviewArtwork() {
        for (key in listOf(
            "https://video.example.test/watch?v=sample-id",
            "https://www.youtube.com.example.test/watch?v=sample-id",
            "https://www.youtube.com/redirect",
            "https://youtu.be/",
            "sample-id?extra",
        )) {
            val unsafe = trailer(id = "invalid", key = key)
            assertNull(selectHeroTrailer(listOf(unsafe)), key)
            assertNull(unsafe.youtubeThumbnailUrl(), key)
            assertNull(unsafe.trailerClickTarget(TrailerPlaybackMode.IN_APP), key)
            assertNull(unsafe.trailerClickTarget(TrailerPlaybackMode.EXTERNAL), key)
        }

        for (url in listOf(
            "relative.mp4", "file:///media/x.mp4", "javascript:noop", "https:/missing-slash",
            "https:///missing-host", "https://user@cdn.example.test/a.mp4", "https://cdn.example.test:99999/a.mp4",
            "https://cdn.example.test/a b.mp4",
        )) {
            val unsafe = trailer(id = "invalid").copy(directUrl = url)
            assertNull(unsafe.trailerClickTarget(TrailerPlaybackMode.IN_APP), url)
            assertNull(unsafe.trailerClickTarget(TrailerPlaybackMode.EXTERNAL), url)
        }
    }



    @Test
    fun `selects official non-season YouTube trailer first`() {
        val trailers = listOf(
            trailer(id = "teaser", type = "Teaser", official = true, publishedAt = "2026-02-01"),
            trailer(id = "season", type = "Trailer", official = true, seasonNumber = 2, publishedAt = "2026-03-01"),
            trailer(id = "official", type = "Trailer", official = true, publishedAt = "2026-01-01"),
        )

        assertEquals("official", selectHeroTrailer(trailers)?.id)
    }

    @Test
    fun `falls back to addon-style YouTube trailer when official flag is absent`() {
        val trailers = listOf(
            trailer(id = "clip", type = "Clip", official = false, publishedAt = "2026-02-01"),
            trailer(id = "addon", type = "Trailer", official = false, publishedAt = "2026-01-01"),
        )

        assertEquals("addon", selectHeroTrailer(trailers)?.id)
    }

    @Test
    fun `uses season trailer when only season trailers are available`() {
        val trailers = listOf(
            trailer(id = "season-one", type = "Trailer", official = false, seasonNumber = 1),
            trailer(id = "season-two", type = "Trailer", official = true, seasonNumber = 2),
        )

        assertEquals("season-two", selectHeroTrailer(trailers)?.id)
    }

    @Test
    fun `filters out non YouTube trailers`() {
        val trailers = listOf(
            trailer(id = "vimeo", site = "Vimeo", type = "Trailer", official = true),
            trailer(id = "youtube", site = "YouTube", type = "Teaser", official = false),
        )

        assertEquals("youtube", selectHeroTrailer(trailers)?.id)
    }

    @Test
    fun `returns null for empty or unsupported trailer lists`() {
        assertNull(selectHeroTrailer(emptyList()))
        assertNull(selectHeroTrailer(listOf(trailer(id = "blank", key = ""))))
        assertNull(selectHeroTrailer(listOf(trailer(id = "other", site = "Vimeo"))))
    }

    private fun trailer(
        id: String,
        key: String = id,
        site: String = "YouTube",
        type: String = "Trailer",
        official: Boolean = false,
        publishedAt: String? = null,
        seasonNumber: Int? = null,
    ): MetaTrailer =
        MetaTrailer(
            id = id,
            key = key,
            name = id,
            site = site,
            type = type,
            official = official,
            publishedAt = publishedAt,
            seasonNumber = seasonNumber,
        )
}
