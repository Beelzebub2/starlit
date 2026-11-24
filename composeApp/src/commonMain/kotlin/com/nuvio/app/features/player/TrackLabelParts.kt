package com.nuvio.app.features.player

/**
 * Splits a raw audio or subtitle track label into the two lines the track panel shows.
 *
 * Release groups often stuff every stream fact into the track title
 * ("Japanese / Opus / 2.0 / 48 kHz / 192 kbps / JPBD (Stereo)"), and the native players append
 * their own channel/codec suffix. Line 1 becomes the language, line 2 keeps the facts that help a
 * viewer choose (codec, channels, bitrate, edition) and drops repeats and sample rates.
 *
 * The desktop player chrome (`player-ui/track-labels.js`) and the Tizen/webOS app
 * (`tizen/js/ui/screens/player/trackLabelParts.js`) mirror these rules; keep all three in sync.
 */
internal enum class TrackKind { Audio, Subtitle }

internal enum class TrackBadge { Forced, Sdh, Commentary, Original, Description }

internal data class TrackLabelParts(
    /** Line 1, or null when neither the language nor the label names the track. */
    val title: String?,
    val badges: List<TrackBadge>,
    /** Line 2, facts joined by " · "; empty when the label has nothing beyond the language. */
    val detail: String,
)

internal fun describeTrackLabel(
    label: String?,
    languageCode: String?,
    languageName: String?,
    kind: TrackKind,
    isForced: Boolean = false,
): TrackLabelParts {
    val code = languageCode?.trim()?.takeUnless { it.isEmpty() || it.equals("und", ignoreCase = true) }
    val knownName = languageName?.trim()?.takeUnless { name ->
        name.isEmpty() || (code != null && name.equals(code, ignoreCase = true))
    }

    var codec: String? = null
    var atmos = false
    var channels: String? = null
    var bitrate: String? = null
    val badges = linkedSetOf<TrackBadge>()
    if (isForced) badges += TrackBadge.Forced
    val texts = mutableListOf<String>()

    tokenizeTrackLabel(label).forEach { token ->
        // A bare language code ("hi", "ad") must not be mistaken for a keyword badge.
        if (token.isTrackLanguageCode(code)) return@forEach
        classifyTrackTokens(token).forEach { part -> when (part) {
            TrackToken.Drop -> Unit
            is TrackToken.Codec -> {
                if (part.atmos) atmos = true
                if (part.name != null && codec == null) codec = part.name
                if (part.channels != null && channels == null) channels = part.channels
            }
            is TrackToken.Channels -> if (channels == null) channels = part.label
            is TrackToken.Bitrate -> if (bitrate == null) bitrate = part.label
            is TrackToken.Badge -> {
                badges += part.badge
                part.text?.let(texts::add)
            }
            is TrackToken.Text -> texts += part.text
        } }
    }

    val title = knownName ?: texts.firstOrNull { !it.isTrackLanguageCode(code) }
    val extras = texts.filter { text ->
        text != title && !text.isTrackLanguageCode(code) && !(title?.containsWord(text) ?: false)
    }
    val codecName = when {
        codec != null && atmos && "Atmos" !in codec -> "$codec Atmos"
        codec == null && atmos -> "Dolby Atmos"
        else -> codec
    }
    val facts = when (kind) {
        TrackKind.Audio -> listOfNotNull(codecName, channels, bitrate) + extras
        TrackKind.Subtitle -> extras + listOfNotNull(codecName)
    }
    val seen = mutableSetOf<String>()
    val detail = facts.filter { seen.add(it.lowercase()) }.joinToString(" · ")
    return TrackLabelParts(title = title, badges = badges.toList(), detail = detail)
}

private val TrackTokenSeparators = Regex("""\s*[/|,•·;]\s*|\s+[-–—]\s+|[()\[\]{}]""")
private val GeneratedTrackName = Regex("""^(track|audio|subtitle|subtitles|sub)\s*#?\d+$""", RegexOption.IGNORE_CASE)
private val SampleRate = Regex("""^\d+(?:[.,]\d+)?\s*k?hz$""", RegexOption.IGNORE_CASE)
private val BitDepth = Regex("""^\d{2}\s*-?\s*bits?$""", RegexOption.IGNORE_CASE)
private val Bitrate = Regex("""^(\d+(?:[.,]\d+)?)\s*([km])\s*(?:b(?:it)?s?)?\s*(?:ps|/s)$""", RegexOption.IGNORE_CASE)
private val ChannelLayout = Regex("""^(\d)\.(\d)(?:\s*\(?(?:side|back|wide)\)?)?(?:\s*(?:ch|channels?|surround))?$""", RegexOption.IGNORE_CASE)
private val ChannelCount = Regex("""^(\d{1,2})\s*(?:ch|channels?)$""", RegexOption.IGNORE_CASE)
private val EmbeddedLayout = Regex("""([1-9])\.([01])$""")
private val TrackWordSeparators = Regex("""[\s\-_.,:;/()\[\]'"&+]+""")

private sealed interface TrackToken {
    data object Drop : TrackToken
    data class Codec(val name: String?, val atmos: Boolean = false, val channels: String? = null) : TrackToken
    data class Channels(val label: String) : TrackToken
    data class Bitrate(val label: String) : TrackToken
    data class Badge(val badge: TrackBadge, val text: String? = null) : TrackToken
    data class Text(val text: String) : TrackToken
}

private fun tokenizeTrackLabel(label: String?): List<String> =
    label.orEmpty()
        .split(TrackTokenSeparators)
        .map { it.trim().trim('-', '–', '—', ':', '.').trim() }
        .filter { it.isNotEmpty() }

/** "Opus Stereo" is two facts; split a space-joined token when every word is a stream fact. */
private fun classifyTrackTokens(token: String): List<TrackToken> {
    val whole = classifyTrackToken(token)
    if (whole !is TrackToken.Text || ' ' !in token.trim()) return listOf(whole)
    val words = token.trim().split(Regex("""\s+""")).map(::classifyTrackToken)
    return if (words.none { it is TrackToken.Text || it is TrackToken.Badge }) words else listOf(whole)
}

private fun classifyTrackToken(token: String): TrackToken {
    val key = token.lowercase().replace(Regex("""[\s_]+"""), " ").trim()
    val compact = key.replace(Regex("""[^a-z0-9+:]"""), "")
    if (key in DroppedWords || GeneratedTrackName.matches(key) || SampleRate.matches(key) || BitDepth.matches(key)) {
        return TrackToken.Drop
    }
    Bitrate.matchEntire(key)?.let { match ->
        val value = match.groupValues[1].replace(',', '.')
        val unit = if (match.groupValues[2].equals("m", ignoreCase = true)) "Mbps" else "kbps"
        return TrackToken.Bitrate("$value $unit")
    }
    channelLabel(key)?.let { return TrackToken.Channels(it) }
    codecToken(compact)?.let { return it }
    badgeToken(key, token)?.let { return it }
    return TrackToken.Text(token)
}

private fun channelLabel(key: String): String? {
    when (key) {
        "mono" -> return "Mono"
        "stereo", "dual mono" -> return "Stereo"
        "surround" -> return "Surround"
    }
    ChannelLayout.matchEntire(key)?.let { match ->
        return layoutName(match.groupValues[1].toInt(), match.groupValues[2].toInt())
    }
    ChannelCount.matchEntire(key)?.let { match ->
        return when (val count = match.groupValues[1].toInt()) {
            1 -> "Mono"
            2 -> "Stereo"
            3 -> "2.1"
            6 -> "5.1"
            7 -> "6.1"
            8 -> "7.1"
            else -> "$count ch"
        }
    }
    return null
}

private fun layoutName(main: Int, lfe: Int): String = when {
    main == 1 && lfe == 0 -> "Mono"
    main == 2 && lfe == 0 -> "Stereo"
    else -> "$main.$lfe"
}

private fun codecToken(compact: String): TrackToken.Codec? {
    val atmos = "atmos" in compact || "joc" in compact
    val embeddedChannels = EmbeddedLayout.find(compact.replace(Regex("""(\d)(\d)$"""), "$1.$2"))
        ?.let { layoutName(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
    fun codec(name: String?) = TrackToken.Codec(name, atmos, embeddedChannels.takeIf { compact.length > 4 })
    return when {
        compact == "atmos" || compact == "dolbyatmos" -> TrackToken.Codec(null, atmos = true)
        compact.startsWith("eac3") || compact.startsWith("ddp") || compact.startsWith("dd+") ||
            compact.startsWith("dolbydigitalplus") -> codec("Dolby Digital Plus")
        "truehd" in compact -> codec("Dolby TrueHD")
        compact.startsWith("ac3") || compact == "dd" || compact.matches(Regex("""dd\d{1,2}""")) ||
            compact == "dolbydigital" -> codec("Dolby Digital")
        compact.startsWith("dtshdma") || compact == "dtshdmasteraudio" || compact == "dtsma" -> codec("DTS-HD MA")
        compact.startsWith("dtshd") -> codec("DTS-HD")
        compact == "dts:x" || compact == "dtsx" -> codec("DTS:X")
        compact == "dca" || compact.matches(Regex("""dts\d{0,2}""")) -> codec("DTS")
        compact.matches(Regex("""(he)?aac(lc|v2)?\d{0,2}""")) -> codec("AAC")
        compact.matches(Regex("""opus\d{0,2}""")) -> codec("Opus")
        compact == "vorbis" -> codec("Vorbis")
        compact.matches(Regex("""flac\d{0,2}""")) -> codec("FLAC")
        compact == "alac" -> codec("ALAC")
        compact.matches(Regex("""mp3\d{0,2}""")) -> codec("MP3")
        compact == "mp2" -> codec("MP2")
        compact.startsWith("pcm") || compact.startsWith("lpcm") || compact == "wav" -> codec("PCM")
        compact == "ass" || compact == "ssa" -> codec("ASS")
        compact == "srt" || compact == "subrip" -> codec("SRT")
        compact == "pgs" || compact.startsWith("hdmvpgs") -> codec("PGS")
        compact == "vtt" || compact == "webvtt" -> codec("WebVTT")
        compact == "ttml" || compact == "dfxp" -> codec("TTML")
        compact == "movtext" || compact == "tx3g" -> codec("TX3G")
        compact == "dvb" || compact.startsWith("dvbsub") -> codec("DVB")
        compact == "vobsub" || compact.startsWith("dvdsub") -> codec("VobSub")
        else -> null
    }
}

private fun badgeToken(key: String, original: String): TrackToken.Badge? {
    val badge = when {
        key == "forced" || key.startsWith("forced ") || key.endsWith(" forced") -> TrackBadge.Forced
        key in SdhWords || key.startsWith("sdh ") || key.endsWith(" sdh") -> TrackBadge.Sdh
        "commentary" in key -> TrackBadge.Commentary
        key == "original" || key == "original audio" || key == "original language" || key == "orig" -> TrackBadge.Original
        key in DescriptionWords -> TrackBadge.Description
        else -> return null
    }
    val leftover = key in BadgeOnlyWords
    return TrackToken.Badge(badge, text = original.takeUnless { leftover })
}

private val DroppedWords = setOf("unknown", "und", "default", "n/a", "na", "none", "audio", "subtitle", "subtitles", "sub", "subs")
private val SdhWords = setOf("sdh", "cc", "hi", "hoh", "hearing impaired", "hard of hearing", "closed captions", "closed caption")
private val DescriptionWords = setOf("ad", "audio description", "audio described", "descriptive", "descriptive audio", "described video", "dvs")
private val BadgeOnlyWords = setOf(
    "forced", "sdh", "cc", "hi", "hoh", "hearing impaired", "hard of hearing", "closed captions", "closed caption",
    "commentary", "original", "original audio", "original language", "orig",
    "ad", "audio description", "audio described", "descriptive", "descriptive audio", "described video", "dvs",
)

private fun String.isTrackLanguageCode(code: String?): Boolean =
    code != null && (equals(code, ignoreCase = true) || equals(code.substringBefore('-'), ignoreCase = true))

/** True when [part] already appears in this title, so repeating it on line 2 adds nothing. */
private fun String.containsWord(part: String): Boolean {
    if (part.length < 2) return false
    val title = lowercase()
    val needle = part.lowercase()
    if (title == needle) return true
    if (needle.length >= 4 && title.contains(needle)) return true
    return title.split(TrackWordSeparators).any { word -> word.isNotEmpty() && word.startsWith(needle) }
}
