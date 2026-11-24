package com.nuvio.app.features.player

internal val SubtitleStyleState.assFontScale: Double
    get() = (fontSizeSp.toDouble() / SubtitleStyleState.DEFAULT.fontSizeSp).coerceIn(0.25, 3.0)

internal data class MpvSubtitleSizing(
    val overrideMode: String,
    val fontScale: Double,
    val verticalPosition: Int,
    val customAss: Boolean = false,
)

internal fun resolveMpvSubtitleSizing(
    codec: String?,
    assFontScale: Double,
    plainOverrideMode: String,
    plainVerticalPosition: Int,
    customizeAss: Boolean = false,
    customAssVerticalPosition: Int = plainVerticalPosition,
): MpvSubtitleSizing {
    val normalizedCodec = codec?.trim()
    val isAss = normalizedCodec.equals("ass", ignoreCase = true) || normalizedCodec.equals("ssa", ignoreCase = true)
    if (!isAss) {
        return MpvSubtitleSizing(
            overrideMode = if (normalizedCodec.isNullOrEmpty()) "no" else plainOverrideMode,
            fontScale = 1.0,
            verticalPosition = plainVerticalPosition,
        )
    }
    if (customizeAss) {
        return MpvSubtitleSizing("force", 1.0, customAssVerticalPosition, customAss = true)
    }
    val scale = assFontScale.takeIf { it.isFinite() }?.coerceIn(0.25, 3.0) ?: 1.0
    return MpvSubtitleSizing(
        overrideMode = if (scale == 1.0) "no" else "scale",
        fontScale = scale,
        verticalPosition = 100,
    )
}
