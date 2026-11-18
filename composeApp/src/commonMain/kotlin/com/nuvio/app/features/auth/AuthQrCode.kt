package com.nuvio.app.features.auth

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlin.math.floor

/** Square QR module grid; `true` marks a dark module. */
internal class AuthQrMatrix(val size: Int, private val modules: BooleanArray) {
    init {
        require(size > 0 && modules.size == size * size) { "QR matrix must be square" }
    }

    operator fun get(x: Int, y: Int): Boolean = modules[y * size + x]
}

/** Encodes a sign-in link as a QR matrix, or returns null where no encoder ships (iOS). */
internal expect fun encodeAuthQr(payload: String): AuthQrMatrix?

/** Encodes [payload] once per value; null means the caller should fall back to the typed code. */
@Composable
internal fun rememberAuthQr(payload: String): AuthQrMatrix? =
    remember(payload) { runCatching { encodeAuthQr(payload) }.getOrNull() }

/**
 * Draws [matrix] with whole-pixel modules so phone cameras read it reliably. Callers place it on a
 * light tile; the tile's padding provides the quiet zone.
 */
@Composable
internal fun AuthQrCanvas(
    matrix: AuthQrMatrix,
    contentDescription: String,
    modifier: Modifier = Modifier,
    moduleColor: Color = Color(0xFF0B0B10),
) {
    Canvas(
        modifier
            .testTag("auth-sign-in-qr")
            .semantics { this.contentDescription = contentDescription },
    ) {
        val cell = floor(minOf(size.width, size.height) / matrix.size)
        if (cell <= 0f) return@Canvas
        val drawn = cell * matrix.size
        val originX = floor((size.width - drawn) / 2f)
        val originY = floor((size.height - drawn) / 2f)
        for (y in 0 until matrix.size) {
            for (x in 0 until matrix.size) {
                if (matrix[x, y]) {
                    drawRect(
                        color = moduleColor,
                        topLeft = Offset(originX + x * cell, originY + y * cell),
                        size = Size(cell, cell),
                    )
                }
            }
        }
    }
}
