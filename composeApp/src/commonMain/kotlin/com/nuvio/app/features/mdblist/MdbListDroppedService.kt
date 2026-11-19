package com.nuvio.app.features.mdblist

import com.nuvio.app.features.tracking.TrackingMediaReference
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Instant

/**
 * Marks a whole show dropped or clears it (`POST /sync/dropped` and `/sync/dropped/remove`, both documented in
 * MDBList's OpenAPI schema). MDBList only drops shows that already have watched state; anything else is reported
 * as not found, which is surfaced instead of being treated as success.
 */
class MdbListDroppedService(
    private val api: MdbListApiClient,
    private val sync: MdbListSyncRepository,
    private val now: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
) {
    suspend fun set(scope: MdbListAuthScope, media: TrackingMediaReference, dropped: Boolean) {
        val ids = media.ids.toMdbListIds() ?: throw IllegalArgumentException("MDBList cannot match this title")
        val body = mdbListDroppedPayload(ids, if (dropped) Instant.fromEpochMilliseconds(now()).toString() else null)
        sync.write(scope, setOf(MdbListSyncBucket.DROPPED)) { snapshot ->
            val response = api.post(if (dropped) "/sync/dropped" else "/sync/dropped/remove", body, scope)
            val missing = mdbListDroppedNotFound(response.body)
            if (missing > 0 && dropped) {
                throw IllegalStateException("MDBList can only drop a show that is already in your watched history")
            }
            val others = snapshot.dropped.filterNot { it.season == null && it.ids.matches(ids) }
            snapshot.copy(dropped = if (dropped) others + MdbListDroppedRecord(ids) else others) to Unit
        }
    }
}

internal fun mdbListDroppedPayload(ids: MdbListIds, droppedAt: String?): String = buildJsonObject {
    put("shows", buildJsonArray {
        add(buildJsonObject {
            put("ids", ids.requestIds())
            droppedAt?.let { put("dropped_at", it) }
        })
    })
}.toString()

/** Number of titles MDBList did not find (or errored on) in a dropped write response. */
internal fun mdbListDroppedNotFound(body: String): Int {
    val payload: JsonObject = mdbListResponseElement(body).objectValue()
    val missing = payload.objectValue("not_found")?.values.orEmpty().sumOf { value ->
        when (value) {
            is JsonArray -> value.size
            else -> value.toString().toIntOrNull()?.coerceAtLeast(0) ?: throw MdbListDecodingException()
        }
    }
    return missing + payload.arrayValue("errors").size
}
