package com.nuvio.app.features.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.text.SubtitleParser
import io.github.peerless2012.ass.AssRender
import io.github.peerless2012.ass.media.AssHandler
import io.github.peerless2012.ass.media.extractor.AssMatroskaExtractor
import io.github.peerless2012.ass.media.kt.withAssSupport
import io.github.peerless2012.ass.media.parser.AssSubtitleParserFactory
import io.github.peerless2012.ass.media.type.AssRenderType
import java.util.Collections
import java.util.WeakHashMap

private val assHandlersByPlayer = Collections.synchronizedMap(WeakHashMap<ExoPlayer, AssHandler>())
private val assFontScales = WeakHashMap<AssHandler, AssFontScaleState>()

private class AssFontScaleState(initialScale: Float) {
    @Volatile
    var scale: Float = initialScale
    var callback: ((AssRender?) -> Unit)? = null
}

@OptIn(UnstableApi::class)
internal fun ExoPlayer.Builder.buildWithAssSupportCompat(
    context: Context,
    renderType: AssRenderType = AssRenderType.CUES,
    dataSourceFactory: DataSource.Factory = DefaultDataSource.Factory(context),
    extractorsFactory: ExtractorsFactory = DefaultExtractorsFactory(),
    renderersFactory: RenderersFactory = DefaultRenderersFactory(context)
): ExoPlayer {
    val assHandler = AssHandler(renderType)
    var player: ExoPlayer? = null
    try {
        val mediaSourceFactory = createAssMediaSourceFactoryCompat(
            assHandler = assHandler,
            dataSourceFactory = dataSourceFactory,
            extractorsFactory = extractorsFactory,
        )
        val createdPlayer = this
            .setMediaSourceFactory(mediaSourceFactory)
            .setRenderersFactory(renderersFactory.withAssSupport(assHandler))
            .build()
        player = createdPlayer
        assHandler.init(createdPlayer)
        assHandlersByPlayer[createdPlayer] = assHandler
        return createdPlayer
    } catch (failure: Throwable) {
        player?.let { runCatching { it.release() } }
        runCatching { assHandler.release() }
        throw failure
    }
}

/** Split video/audio playback must use the same ASS-aware subtitle parser. */
@OptIn(UnstableApi::class)
internal fun ExoPlayer.createAssMediaSourceFactoryCompat(
    dataSourceFactory: DataSource.Factory,
    extractorsFactory: ExtractorsFactory,
): DefaultMediaSourceFactory? {
    val handler = getAssHandlerCompat() ?: return null
    return createAssMediaSourceFactoryCompat(handler, dataSourceFactory, extractorsFactory)
}

@OptIn(UnstableApi::class)
private fun createAssMediaSourceFactoryCompat(
    assHandler: AssHandler,
    dataSourceFactory: DataSource.Factory,
    extractorsFactory: ExtractorsFactory,
): DefaultMediaSourceFactory {
    val parser = CompatAssSubtitleParserFactory(assHandler)
    val assExtractorsFactory = extractorsFactory.withAssMkvSupportCompat(parser, assHandler)
    return DefaultMediaSourceFactory(dataSourceFactory, assExtractorsFactory).apply {
        setSubtitleParserFactory(parser)
    }
}

internal fun ExoPlayer.getAssHandlerCompat(): AssHandler? = assHandlersByPlayer[this]

internal fun AssHandler.applyAssFontScaleCompat(scale: Float) {
    val clamped = scale.takeIf { it.isFinite() }?.coerceIn(0.25f, 3f) ?: 1f
    synchronized(assFontScales) {
        assFontScales.getOrPut(this) { AssFontScaleState(clamped) }.scale = clamped
    }
    rebindAssFontScaleCompat()
}

internal fun AssHandler.rebindAssFontScaleCompat() {
    val scale = synchronized(assFontScales) {
        val state = assFontScales[this] ?: return
        val downstream = renderCallback
        if (state.callback == null || state.callback !== downstream) {
            val callback: (AssRender?) -> Unit = { newRender ->
                newRender?.setFontScale(state.scale)
                downstream?.invoke(newRender)
            }
            renderCallback = callback
            state.callback = callback
        }
        state.scale
    }
    render?.setFontScale(scale)
}

@OptIn(UnstableApi::class)
internal fun ExoPlayer.releaseWithAssSupportCompat() {
    val assHandler = assHandlersByPlayer.remove(this)
    assHandler?.let { handler -> synchronized(assFontScales) { assFontScales.remove(handler) } }
    try {
        release()
    } finally {
        assHandler?.release()
    }
}

@OptIn(UnstableApi::class)
private class CompatAssSubtitleParserFactory(
    private val assHandler: AssHandler
) : SubtitleParser.Factory {
    private val delegate = AssSubtitleParserFactory(assHandler)

    override fun supportsFormat(format: Format): Boolean {
        return delegate.supportsFormat(normalizeSsaFormat(format))
    }

    override fun getCueReplacementBehavior(format: Format): Int {
        return delegate.getCueReplacementBehavior(normalizeSsaFormat(format))
    }

    override fun create(format: Format): SubtitleParser {
        return delegate.create(normalizeSsaFormat(format))
    }

    private fun normalizeSsaFormat(format: Format): Format {
        val isSsaByCodecs = format.codecs == MimeTypes.TEXT_SSA
        val isSsaByMime = format.sampleMimeType == MimeTypes.TEXT_SSA
        if (isSsaByCodecs && !isSsaByMime) {
            return format.buildUpon()
                .setSampleMimeType(MimeTypes.TEXT_SSA)
                .build()
        }
        return format
    }
}

@OptIn(UnstableApi::class)
private fun ExtractorsFactory.withAssMkvSupportCompat(
    subtitleParserFactory: SubtitleParser.Factory,
    assHandler: AssHandler
): ExtractorsFactory {
    return ExtractorsFactory {
        val extractors = createExtractors()
        extractors.forEachIndexed { index, extractor ->
            if (extractor is MatroskaExtractor) {
                extractors[index] = AssMatroskaExtractor(subtitleParserFactory, assHandler)
            }
        }
        extractors
    }
}
