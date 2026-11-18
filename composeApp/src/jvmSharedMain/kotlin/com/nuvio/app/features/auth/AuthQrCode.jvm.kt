package com.nuvio.app.features.auth

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

internal actual fun encodeAuthQr(payload: String): AuthQrMatrix? {
    if (payload.isBlank()) return null
    val bits = QRCodeWriter().encode(
        payload,
        BarcodeFormat.QR_CODE,
        0,
        0,
        mapOf(
            // The light tile around the code provides the quiet zone.
            EncodeHintType.MARGIN to 0,
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        ),
    )
    val size = bits.width
    if (size <= 0 || bits.height != size) return null
    val modules = BooleanArray(size * size) { index -> bits.get(index % size, index / size) }
    return AuthQrMatrix(size, modules)
}
