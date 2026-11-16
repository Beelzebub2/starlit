package com.nuvio.app.features.debrid

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

internal data class LocalDebridCachedItem(
    val name: String?,
    val size: Long?,
)

internal object LocalDebridService {
    suspend fun checkCached(
        account: DebridServiceCredential,
        hashes: List<String>,
    ): Map<String, LocalDebridCachedItem>? =
        when (account.provider.id) {
            DebridProviders.TORBOX_ID -> checkTorboxCached(account.apiKey, hashes)
            DebridProviders.PREMIUMIZE_ID -> checkPremiumizeCached(account.apiKey, hashes)
            else -> null
        }

    suspend fun isCached(account: DebridServiceCredential, hash: String): Boolean? {
        val normalizedHash = hash.trim().lowercase().takeIf { it.isNotBlank() } ?: return null
        return checkCached(account, listOf(normalizedHash))?.containsKey(normalizedHash)
    }

    private suspend fun checkTorboxCached(
        apiKey: String,
        hashes: List<String>,
    ): Map<String, LocalDebridCachedItem>? {
        val normalized = hashes
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .distinct()
        if (normalized.isEmpty()) return emptyMap()
        val cached = requestTorboxCached(apiKey, normalized) ?: return null
        return resolveTorboxAliasMatches(cached, normalized) { hash ->
            requestTorboxCached(apiKey, listOf(hash))
        }
    }

    private suspend fun requestTorboxCached(
        apiKey: String,
        hashes: List<String>,
    ): Map<String, LocalDebridCachedItem>? =
        try {
            val response = TorboxApiClient.checkCached(apiKey = apiKey, hashes = hashes)
            if (!response.isSuccessful || response.body?.success == false) {
                null
            } else {
                response.body?.data.orEmpty().mapKeys { it.key.lowercase() }.mapValues { (_, value) ->
                    LocalDebridCachedItem(
                        name = value.name,
                        size = value.size,
                    )
                }
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            null
        }

    private suspend fun checkPremiumizeCached(
        apiKey: String,
        hashes: List<String>,
    ): Map<String, LocalDebridCachedItem>? =
        try {
            val normalizedHashes = hashes
                .map { it.trim().lowercase() }
                .filter { it.isNotBlank() }
                .distinct()
            if (normalizedHashes.isEmpty()) return emptyMap()
            val sources = normalizedHashes.map { hash -> "magnet:?xt=urn:btih:$hash" }
            val response = PremiumizeApiClient.checkCache(apiKey = apiKey, items = sources)
            val body = response.body
            if (!response.isSuccessful || body?.status.equals("error", ignoreCase = true)) {
                null
            } else {
                normalizedHashes.mapIndexedNotNull { index, hash ->
                    if (body?.response?.getOrNull(index) != true) return@mapIndexedNotNull null
                    hash to LocalDebridCachedItem(
                        name = body.filename?.getOrNull(index),
                        size = body.filesize?.getOrNull(index)?.asLongOrNull(),
                    )
                }.toMap()
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            null
        }
}

private fun kotlinx.serialization.json.JsonElement?.asLongOrNull(): Long? {
    val primitive = this as? JsonPrimitive ?: return null
    return primitive.longOrNull ?: primitive.content.toLongOrNull()
}

// TorBox can return the canonical hash for an alternative hash. A batch response
// does not identify which requested alias produced that canonical key.
internal suspend fun resolveTorboxAliasMatches(
    cached: Map<String, LocalDebridCachedItem>,
    requestedHashes: List<String>,
    fetchCached: suspend (String) -> Map<String, LocalDebridCachedItem>? = { null },
): Map<String, LocalDebridCachedItem>? {
    val requested = requestedHashes
        .map { it.trim().lowercase() }
        .filter { it.isNotBlank() }
        .distinct()
    val normalizedCached = cached.mapKeys { it.key.trim().lowercase() }
    if (requested.isEmpty() || normalizedCached.keys.all { it in requested }) return normalizedCached

    if (requested.size == 1) {
        val item = normalizedCached[requested.single()]
            ?: normalizedCached.values.singleOrNull()
            ?: return null
        return mapOf(requested.single() to item)
    }

    val resolved = normalizedCached.toMutableMap()
    val limit = Semaphore(4)
    val matches = coroutineScope {
        requested.filter { it !in resolved }.map { hash ->
            async {
                val answer = limit.withPermit {
                    try {
                        fetchCached(hash)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        null
                    }
                }
                // A failed or ambiguous lookup is UNKNOWN, not NOT_CACHED.
                val item = answer?.get(hash) ?: answer?.values?.singleOrNull()
                Triple(hash, item, answer != null && (answer.containsKey(hash) || answer.size <= 1))
            }
        }.awaitAll()
    }
    if (matches.any { !it.third }) return null
    matches.forEach { (hash, item, _) ->
        if (item != null) resolved[hash] = item
    }
    return resolved
}
