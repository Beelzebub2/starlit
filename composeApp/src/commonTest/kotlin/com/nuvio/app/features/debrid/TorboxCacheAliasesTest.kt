package com.nuvio.app.features.debrid

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class TorboxCacheAliasesTest {
    private val requested = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    private val canonical = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    private val cachedItem = LocalDebridCachedItem(name = "sample.mkv", size = 1024L)

    @Test
    fun exactResponsesAvoidAdditionalRequests() = runTest {
        val cached = mapOf(requested to cachedItem)
        val result = resolveTorboxAliasMatches(cached, listOf(requested)) {
            error("Exact matches must not trigger extra TorBox requests")
        }
        assertEquals(cached, result)
    }

    @Test
    fun aCanonicalResponseMatchesTheOnlyRequestedAlias() = runTest {
        val result = resolveTorboxAliasMatches(mapOf(canonical to cachedItem), listOf(requested)) {
            error("A single request is unambiguous")
        }
        assertEquals(mapOf(requested to cachedItem), result)
    }

    @Test
    fun batchedAliasesAreMatchedUsingIndividualFollowUpResults() = runTest {
        val exact = "cccccccccccccccccccccccccccccccccccccccc"
        val asked = mutableListOf<String>()
        val result = resolveTorboxAliasMatches(
            cached = mapOf(exact to cachedItem, canonical to cachedItem),
            requestedHashes = listOf(exact, requested),
        ) { hash ->
            asked += hash
            mapOf(canonical to cachedItem)
        }
        assertEquals(listOf(requested), asked)
        assertEquals(cachedItem, result?.get(requested))
        assertEquals(cachedItem, result?.get(exact))
    }

    @Test
    fun ambiguousSingleHashResponseIsNotAnUnprovenCacheHit() = runTest {
        val another = "dddddddddddddddddddddddddddddddddddddddd"
        val result = resolveTorboxAliasMatches(
            mapOf(canonical to cachedItem, another to cachedItem),
            listOf(requested),
        )
        assertEquals(null, result)
    }

    @Test
    fun duplicateAndMixedCaseRequestedHashesAreNormalized() = runTest {
        val result = resolveTorboxAliasMatches(
            mapOf(canonical to cachedItem),
            listOf("  ${requested.uppercase()} ", requested),
        )
        assertEquals(cachedItem, result?.get(requested))
    }

    @Test
    fun aFailedIndividualLookupDoesNotBecomeAnIncorrectCacheMiss() = runTest {
        val result = resolveTorboxAliasMatches(
            mapOf(canonical to cachedItem),
            listOf(requested, "cccccccccccccccccccccccccccccccccccccccc"),
        ) { _ -> null }
        assertEquals(null, result)
    }
}
