// Splits a raw audio or subtitle track label into the two lines the track menus show.
// Copy of composeApp/src/desktopMain/resources/player-ui/track-labels.js (and TrackLabelParts.kt);
// tests/starlit-track-labels.test.mjs fails if the two JavaScript copies drift apart.

const SEPARATORS = /\s*[/|,•·;]\s*|\s+[-–—]\s+|[()[\]{}]/;
const GENERATED_NAME = /^(track|audio|subtitle|subtitles|sub)\s*#?\d+$/i;
const SAMPLE_RATE = /^\d+(?:[.,]\d+)?\s*k?hz$/i;
const BIT_DEPTH = /^\d{2}\s*-?\s*bits?$/i;
const BITRATE = /^(\d+(?:[.,]\d+)?)\s*([km])\s*(?:b(?:it)?s?)?\s*(?:ps|\/s)$/i;
const CHANNEL_LAYOUT = /^(\d)\.(\d)(?:\s*\(?(?:side|back|wide)\)?)?(?:\s*(?:ch|channels?|surround))?$/i;
const CHANNEL_COUNT = /^(\d{1,2})\s*(?:ch|channels?)$/i;
const EMBEDDED_LAYOUT = /([1-9])\.([01])$/;
// No Unicode property escapes: Tizen 4 (Chromium 56) cannot parse them.
const WORD_SEPARATORS = /[\s\-_.,:;/()[\]'"&+]+/;

const DROPPED = new Set(["unknown", "und", "default", "n/a", "na", "none", "audio", "subtitle", "subtitles", "sub", "subs"]);
const SDH = new Set(["sdh", "cc", "hi", "hoh", "hearing impaired", "hard of hearing", "closed captions", "closed caption"]);
const DESCRIPTION = new Set(["ad", "audio description", "audio described", "descriptive", "descriptive audio", "described video", "dvs"]);
const BADGE_ONLY = new Set([
  ...SDH, ...DESCRIPTION,
  "forced", "commentary", "original", "original audio", "original language", "orig",
]);

const layoutName = (main, lfe) => {
  if (main === 1 && lfe === 0) return "Mono";
  if (main === 2 && lfe === 0) return "Stereo";
  return `${main}.${lfe}`;
};

const channelLabel = key => {
  if (key === "mono") return "Mono";
  if (key === "stereo" || key === "dual mono") return "Stereo";
  if (key === "surround") return "Surround";
  let match = key.match(CHANNEL_LAYOUT);
  if (match) return layoutName(Number(match[1]), Number(match[2]));
  match = key.match(CHANNEL_COUNT);
  if (match) {
    const count = Number(match[1]);
    return ({ 1: "Mono", 2: "Stereo", 3: "2.1", 6: "5.1", 7: "6.1", 8: "7.1" })[count] || `${count} ch`;
  }
  return "";
};

const codecToken = compact => {
  const atmos = compact.includes("atmos") || compact.includes("joc");
  const embedded = compact.replace(/(\d)(\d)$/, "$1.$2").match(EMBEDDED_LAYOUT);
  const channels = embedded && compact.length > 4 ? layoutName(Number(embedded[1]), Number(embedded[2])) : "";
  const codec = name => ({ type: "codec", name, atmos, channels });
  if (compact === "atmos" || compact === "dolbyatmos") return { type: "codec", name: "", atmos: true, channels: "" };
  if (compact.startsWith("eac3") || compact.startsWith("ddp") || compact.startsWith("dd+") ||
    compact.startsWith("dolbydigitalplus")) return codec("Dolby Digital Plus");
  if (compact.includes("truehd")) return codec("Dolby TrueHD");
  if (compact.startsWith("ac3") || compact === "dd" || /^dd\d{1,2}$/.test(compact) || compact === "dolbydigital") {
    return codec("Dolby Digital");
  }
  if (compact.startsWith("dtshdma") || compact === "dtshdmasteraudio" || compact === "dtsma") return codec("DTS-HD MA");
  if (compact.startsWith("dtshd")) return codec("DTS-HD");
  if (compact === "dts:x" || compact === "dtsx") return codec("DTS:X");
  if (compact === "dca" || /^dts\d{0,2}$/.test(compact)) return codec("DTS");
  if (/^(he)?aac(lc|v2)?\d{0,2}$/.test(compact)) return codec("AAC");
  if (/^opus\d{0,2}$/.test(compact)) return codec("Opus");
  if (compact === "vorbis") return codec("Vorbis");
  if (/^flac\d{0,2}$/.test(compact)) return codec("FLAC");
  if (compact === "alac") return codec("ALAC");
  if (/^mp3\d{0,2}$/.test(compact)) return codec("MP3");
  if (compact === "mp2") return codec("MP2");
  if (compact.startsWith("pcm") || compact.startsWith("lpcm") || compact === "wav") return codec("PCM");
  if (compact === "ass" || compact === "ssa") return codec("ASS");
  if (compact === "srt" || compact === "subrip") return codec("SRT");
  if (compact === "pgs" || compact.startsWith("hdmvpgs")) return codec("PGS");
  if (compact === "vtt" || compact === "webvtt") return codec("WebVTT");
  if (compact === "ttml" || compact === "dfxp") return codec("TTML");
  if (compact === "movtext" || compact === "tx3g") return codec("TX3G");
  if (compact === "dvb" || compact.startsWith("dvbsub")) return codec("DVB");
  if (compact === "vobsub" || compact.startsWith("dvdsub")) return codec("VobSub");
  return null;
};

const badgeToken = (key, original) => {
  let badge = "";
  if (key === "forced" || key.startsWith("forced ") || key.endsWith(" forced")) badge = "forced";
  else if (SDH.has(key) || key.startsWith("sdh ") || key.endsWith(" sdh")) badge = "sdh";
  else if (key.includes("commentary")) badge = "commentary";
  else if (key === "original" || key === "original audio" || key === "original language" || key === "orig") badge = "original";
  else if (DESCRIPTION.has(key)) badge = "description";
  if (!badge) return null;
  return { type: "badge", badge, text: BADGE_ONLY.has(key) ? "" : original };
};

const classify = token => {
  const key = token.toLowerCase().replace(/[\s_]+/g, " ").trim();
  const compact = key.replace(/[^a-z0-9+:]/g, "");
  if (DROPPED.has(key) || GENERATED_NAME.test(key) || SAMPLE_RATE.test(key) || BIT_DEPTH.test(key)) return { type: "drop" };
  const bitrate = key.match(BITRATE);
  if (bitrate) {
    return { type: "bitrate", label: `${bitrate[1].replace(",", ".")} ${bitrate[2].toLowerCase() === "m" ? "Mbps" : "kbps"}` };
  }
  const channels = channelLabel(key);
  if (channels) return { type: "channels", label: channels };
  return codecToken(compact) || badgeToken(key, token) || { type: "text", text: token };
};

// "Opus Stereo" is two facts; split a space-joined token when every word is a stream fact.
const classifyAll = token => {
  const whole = classify(token);
  if (whole.type !== "text" || !/\s/.test(token.trim())) return [whole];
  const words = token.trim().split(/\s+/).map(classify);
  return words.every(part => part.type !== "text" && part.type !== "badge") ? words : [whole];
};

const tokenize = label => String(label || "")
  .split(SEPARATORS)
  .map(part => (part || "").trim().replace(/^[-–—:.]+|[-–—:.]+$/g, "").trim())
  .filter(Boolean);

const isLanguageCode = (text, code) => Boolean(code) && (
  text.toLowerCase() === code.toLowerCase() || text.toLowerCase() === code.split("-")[0].toLowerCase()
);

const containsWord = (title, part) => {
  if (!title || part.length < 2) return false;
  const haystack = title.toLowerCase();
  const needle = part.toLowerCase();
  if (haystack === needle) return true;
  if (needle.length >= 4 && haystack.includes(needle)) return true;
  return haystack.split(WORD_SEPARATORS).some(word => word && word.startsWith(needle));
};

/**
 * @param {{label?: string, language?: string, languageName?: string, kind?: "audio"|"subtitle", forced?: boolean}} track
 * @returns {{title: string, badges: string[], detail: string}} title is "" when the caller must supply a fallback.
 */
export const describeTrack = ({ label, language, languageName, kind = "audio", forced = false } = {}) => {
  const rawCode = String(language || "").trim();
  const code = rawCode && rawCode.toLowerCase() !== "und" ? rawCode : "";
  const name = String(languageName || "").trim();
  const knownName = name && !(code && name.toLowerCase() === code.toLowerCase()) ? name : "";

  let codec = "";
  let atmos = false;
  let channels = "";
  let bitrate = "";
  const badges = [];
  const addBadge = badge => { if (!badges.includes(badge)) badges.push(badge); };
  if (forced) addBadge("forced");
  const texts = [];

  tokenize(label).forEach(token => {
    if (isLanguageCode(token, code)) return;
    classifyAll(token).forEach(part => { switch (part.type) {
      case "codec":
        if (part.atmos) atmos = true;
        if (part.name && !codec) codec = part.name;
        if (part.channels && !channels) channels = part.channels;
        break;
      case "channels":
        if (!channels) channels = part.label;
        break;
      case "bitrate":
        if (!bitrate) bitrate = part.label;
        break;
      case "badge":
        addBadge(part.badge);
        if (part.text) texts.push(part.text);
        break;
      case "text":
        texts.push(part.text);
        break;
      default:
        break;
    } });
  });

  const title = knownName || texts.find(text => !isLanguageCode(text, code)) || "";
  const extras = texts.filter(text => text !== title && !isLanguageCode(text, code) && !containsWord(title, text));
  let codecName = codec;
  if (codec && atmos && !codec.includes("Atmos")) codecName = `${codec} Atmos`;
  else if (!codec && atmos) codecName = "Dolby Atmos";
  const facts = kind === "subtitle"
    ? [...extras, codecName]
    : [codecName, channels, bitrate, ...extras];
  const seen = new Set();
  const detail = facts
    .filter(Boolean)
    .filter(fact => {
      const key = fact.toLowerCase();
      if (seen.has(key)) return false;
      seen.add(key);
      return true;
    })
    .join(" · ");
  return { title, badges, detail };
};
