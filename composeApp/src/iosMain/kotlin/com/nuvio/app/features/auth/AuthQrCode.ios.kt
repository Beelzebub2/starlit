package com.nuvio.app.features.auth

/** iOS ships no QR encoder; the sign-in screen falls back to the typed code. */
internal actual fun encodeAuthQr(payload: String): AuthQrMatrix? = null
