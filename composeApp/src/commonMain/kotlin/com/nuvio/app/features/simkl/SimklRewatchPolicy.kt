package com.nuvio.app.features.simkl

internal class SimklRewatchAuthorization(
    val current: () -> Boolean,
    val onPlanRejected: () -> Unit,
)

internal fun isSimklRewatchPlan(accountType: String?): Boolean =
    when (accountType?.trim()?.lowercase()) {
        "pro", "vip" -> true
        else -> false
    }

internal data class SimklRewatchStop(val threshold: Int, val record: Boolean) {
    val query: Map<String, String>
        get() = buildMap {
            put("min_progress", threshold.toString())
            if (record) put("allow_rewatch", "yes")
        }
}

internal fun resolveSimklRewatchStop(
    progressPercent: Double,
    startPercent: Double?,
    creditsPercent: Double?,
    explicitlySelected: Boolean,
): SimklRewatchStop? {
    if (!explicitlySelected || startPercent == null || !startPercent.isFinite() || startPercent !in 0.0..100.0 ||
        !progressPercent.isFinite() || progressPercent !in 0.0..100.0
    ) return null

    val threshold = creditsPercent?.takeIf { it.isFinite() && it in 80.0..<100.0 }?.toInt() ?: 80
    return SimklRewatchStop(threshold, record = startPercent < threshold && progressPercent >= threshold)
}
