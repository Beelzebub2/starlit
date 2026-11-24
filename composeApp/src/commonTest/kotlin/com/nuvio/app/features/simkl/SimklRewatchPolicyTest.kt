package com.nuvio.app.features.simkl

import com.nuvio.app.features.addons.RawHttpResponse
import com.nuvio.app.features.tracking.TrackingMediaKind
import com.nuvio.app.features.tracking.TrackingMediaReference
import com.nuvio.app.features.tracking.TrackingScrobbleAction
import com.nuvio.app.features.tracking.TrackingScrobbleEvent
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SimklRewatchPolicyTest {
    @Test
    fun onlyKnownPaidPlansAreEligible() {
        assertTrue(isSimklRewatchPlan("pro"))
        assertTrue(isSimklRewatchPlan("VIP"))
        listOf(null, "", "free", "vip_trial", "unknown").forEach {
            assertFalse(isSimklRewatchPlan(it))
        }
    }

    @Test
    fun rewatchRequiresConfirmedPlaybackStartingBelowTheCompletionMarker() {
        assertNull(resolveSimklRewatchStop(100.0, 3.0, 90.0, explicitlySelected = false))
        assertNull(resolveSimklRewatchStop(100.0, null, 90.0, explicitlySelected = true))
        assertNull(resolveSimklRewatchStop(Double.NaN, 0.0, 90.0, explicitlySelected = true))
        assertEquals(mapOf("min_progress" to "94"), resolveSimklRewatchStop(89.0, 3.0, 94.8, explicitlySelected = true)?.query)
        assertEquals(mapOf("min_progress" to "94"), resolveSimklRewatchStop(100.0, 94.0, 94.8, explicitlySelected = true)?.query)
        assertEquals(
            mapOf("min_progress" to "94", "allow_rewatch" to "yes"),
            resolveSimklRewatchStop(94.0, 3.0, 94.8, explicitlySelected = true)?.query,
        )
        assertEquals(
            mapOf("min_progress" to "80", "allow_rewatch" to "yes"),
            resolveSimklRewatchStop(100.0, 3.0, 35.0, explicitlySelected = true)?.query,
        )
    }

    @Test
    fun onlyEligibleCompletedStopUsesRewatchQuery() = runBlocking {
        val urls = mutableListOf<String>()
        val service = fixture(urls)
        val event = event(92.0)
        service.scrobble(TrackingScrobbleAction.STOP, event)
        service.scrobble(TrackingScrobbleAction.PAUSE, event, SimklRewatchStop(90, record = true))
        val partial = service.scrobble(TrackingScrobbleAction.STOP, event, SimklRewatchStop(95, record = false))
        service.scrobble(TrackingScrobbleAction.STOP, event, SimklRewatchStop(90, record = true))
        assertEquals(4, urls.size)
        assertTrue(urls.take(3).none { "allow_rewatch" in it })
        assertTrue("min_progress=95" in urls[2])
        assertEquals(SimklScrobbleOutcome.PAUSE, partial.outcome)
        assertTrue("allow_rewatch=yes" in urls.last())
        assertTrue("min_progress=90" in urls.last())
    }

    @Test
    fun optingOutWhileRequestWaitsPreventsNetworkAccess() = runBlocking {
        val urls = mutableListOf<String>()
        val service = fixture(urls)
        var checks = 0
        assertFails {
            service.scrobble(
                TrackingScrobbleAction.STOP,
                event(100.0),
                SimklRewatchStop(90, record = true),
                authorizationIsCurrent = { ++checks < 3 },
            )
        }
        assertTrue(urls.isEmpty())
    }

    @Test
    fun planRefusalIsNotSilentlyTreatedAsRecorded() = runBlocking {
        val service = fixture(mutableListOf(), """{"rewatch_status":"pro_required"}""")
        val result = service.scrobble(TrackingScrobbleAction.STOP, event(100.0), SimklRewatchStop(90, true))
        assertTrue(result.rewatchPlanRejected)
    }

    private fun event(progress: Double) = TrackingScrobbleEvent(
        media = TrackingMediaReference(kind = TrackingMediaKind.MOVIE, title = "Synthetic test movie"),
        progressPercent = progress,
    )

    private fun fixture(urls: MutableList<String>, body: String = "{}") = SimklMutationService(
        client = SimklApiClient(
            engine = SimklHttpEngine { _, url, _, _ ->
                urls += url
                RawHttpResponse(status = 200, statusText = "", url = url, body = body, headers = emptyMap())
            },
            accessToken = { "fixture-token" },
            onUnauthorized = {},
            nowEpochMs = { 0L },
            sleep = {},
            retryJitterMs = { 0L },
        ),
    )
}
