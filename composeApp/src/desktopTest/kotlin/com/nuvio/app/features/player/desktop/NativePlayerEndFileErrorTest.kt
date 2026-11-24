package com.nuvio.app.features.player.desktop

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NativePlayerEndFileErrorTest {
    @Test
    fun acceptsOnlyOneAsynchronousErrorFromTheCurrentSource() {
        val sinks = (1..2).map { AtomicReference<NativePlayerEventSink>() }
        val started = (1..2).map { CountDownLatch(1) }
        val releaseNative = (1..2).map { CountDownLatch(1) }
        val messages = mutableListOf<String>()
        val controller = NativePlayerController(
            host = NativePlayerHost(),
            nativeCreate = { _, uri, _, _, _, _, _, _, sink ->
                val index = if (uri.endsWith("/old")) 0 else 1
                sinks[index].set(sink)
                started[index].countDown()
                releaseNative[index].await(5, TimeUnit.SECONDS)
                41L + index
            },
            nativeDispose = {},
            isHostDisplayable = { true },
            resolveHostView = { 1L },
            playbackErrorMessage = { "Localized failure" },
        )
        fun attach(path: String) = controller.attach(
            sourceUrl = "https://example.invalid/" + path,
            sourceHeaders = emptyMap(),
            playWhenReady = true,
            initialPositionMs = 0L,
            decoderPriority = 0,
            nvidiaRtxSuperResolutionEnabled = false,
            onError = { messages += path + ": " + it },
        )
        fun report(index: Int) = checkNotNull(sinks[index].get()).onPlayerEvent("mpvEndFileError", -13.0)

        try {
            attach("old")
            assertTrue(started[0].await(2, TimeUnit.SECONDS))
            controller.dispose()
            attach("current")
            report(0)
            releaseNative[0].countDown()
            assertTrue(started[1].await(2, TimeUnit.SECONDS))
            report(1)
            report(1)
            SwingUtilities.invokeAndWait {}
            assertEquals(listOf("current: Localized failure"), messages)

            controller.releaseBeforeNavigation {}
            report(1)
            SwingUtilities.invokeAndWait {}
            assertEquals(listOf("current: Localized failure"), messages)
        } finally {
            controller.dispose()
            releaseNative.forEach { it.countDown() }
        }
    }
    @Test
    fun ignoresTheFirstErrorIfThePlayerHasAlreadyBeenReleased() {
        val sink = AtomicReference<NativePlayerEventSink>()
        val started = CountDownLatch(1)
        val releaseNative = CountDownLatch(1)
        val messages = mutableListOf<String?>()
        val controller = NativePlayerController(
            host = NativePlayerHost(),
            nativeCreate = { _, _, _, _, _, _, _, _, eventSink ->
                sink.set(eventSink)
                started.countDown()
                releaseNative.await(5, TimeUnit.SECONDS)
                42L
            },
            nativeDispose = {},
            isHostDisplayable = { true },
            resolveHostView = { 1L },
            playbackErrorMessage = { "Localized failure" },
        )
        try {
            controller.attach(
                sourceUrl = "https://example.invalid/current",
                sourceHeaders = emptyMap(),
                playWhenReady = true,
                initialPositionMs = 0L,
                decoderPriority = 0,
                nvidiaRtxSuperResolutionEnabled = false,
                onError = { messages += it },
            )
            assertTrue(started.await(2, TimeUnit.SECONDS))
            controller.releaseBeforeNavigation {}
            checkNotNull(sink.get()).onPlayerEvent("mpvEndFileError", -13.0)
            SwingUtilities.invokeAndWait {}
            assertTrue(messages.isEmpty())
        } finally {
            controller.dispose()
            releaseNative.countDown()
        }
    }
}
