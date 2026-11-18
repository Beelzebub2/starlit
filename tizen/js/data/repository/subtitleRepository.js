import { safeApiCall } from "../../core/network/safeApiCall.js";
import { addonRepository } from "./addonRepository.js";
import { catalogRepository } from "./catalogRepository.js";
import { TmdbService } from "../../core/tmdb/tmdbService.js";
import { SubtitleApi } from "../remote/api/subtitleApi.js";
const PER_ADDON_TIMEOUT_MS = 20000;
const RELEASE_LOOKUP_TIMEOUT_MS = 4000;
const CINEMETA_BASE_URL = "https://v3-cinemeta.strem.io";
function normalizeMatchText(value = "") {
  return String(value || "")
    .trim()
    .toLowerCase()
    .replace(/\s+/g, " ")
    .trim();
}
function releaseYear(value = "") {
  return String(value || "").match(/\b(19|20)\d{2}\b/)?.[0] || "";
}
export function selectCanonicalCinemetaId(items = [], { type, title, year } = {}) {
  const rawType = String(type || "")
    .trim()
    .toLowerCase();
  const normalizedType = rawType === "tv" ? "series" : rawType;
  const normalizedTitle = normalizeMatchText(title);
  const normalizedYear = releaseYear(year);
  if (!["movie", "series"].includes(normalizedType) || !normalizedTitle || !normalizedYear) {
    return "";
  }
  const matches = (items || []).filter((item) => {
    const rawItemType = String(item?.type || "")
      .trim()
      .toLowerCase();
    const itemType = rawItemType === "tv" ? "series" : rawItemType;
    return (
      /^tt\d+$/i.test(String(item?.id || "").trim()) &&
      itemType === normalizedType &&
      normalizeMatchText(item?.name) === normalizedTitle &&
      releaseYear(item?.releaseInfo || item?.year) === normalizedYear
    );
  });
  const ids = Array.from(new Set(matches.map((item) => String(item.id).trim())));
  return ids.length === 1 ? ids[0] : "";
}
export function buildSubtitleIdCandidates({
  type,
  ids = [],
  videoId = null,
  season = null,
  episode = null,
  idPrefixes = []
} = {}) {
  const candidates = [];
  const push = (value) => {
    const normalized = String(value || "").trim();
    if (!normalized || candidates.includes(normalized)) {
      return;
    }
    candidates.push(normalized);
  };
  const normalizedType = String(type || "")
    .trim()
    .toLowerCase();
  const seasonNumber = Number(season);
  const episodeNumber = Number(episode);
  if (normalizedType === "series") {
    if (videoId && !/^tt\d+$/i.test(String(videoId).trim())) push(videoId);
    if (
      season != null &&
      Number.isInteger(seasonNumber) &&
      seasonNumber >= 0 &&
      Number.isInteger(episodeNumber) &&
      episodeNumber > 0
    ) {
      (ids || []).forEach((id) => {
        if (/^tt\d+$/i.test(String(id || "").trim())) {
          push(`${String(id).trim()}:${seasonNumber}:${episodeNumber}`);
        }
      });
    }
  } else {
    (ids || []).forEach(push);
  }
  const prefixes = (idPrefixes || []).map((value) => String(value || "").trim()).filter(Boolean);
  return prefixes.length
    ? candidates.filter((candidate) => prefixes.some((prefix) => candidate.startsWith(prefix)))
    : candidates;
}
class SubtitleRepository {
  async getSubtitles(type, id, videoId = null, options = {}) {
    const normalizedType = this.canonicalSubtitleType(type);
    const rawId = String(id || "").trim();
    const existingImdb = [options.imdbId, rawId, videoId]
      .map((candidate) => String(candidate || "").trim().split(":")[0])
      .find((candidate) => /^tt\d+$/i.test(candidate));
    const [canonicalId, addons] = await Promise.all([
      existingImdb || this.resolveCanonicalId(normalizedType, options),
      addonRepository.getInstalledAddons({ cacheOnly: Boolean(options?.manifestCacheOnly) })
    ]);
    const idCandidates = this.uniqueNonEmpty([rawId, canonicalId]);
    const allResults = await Promise.all(
      addons.map((addon) => this.fetchSubtitlesFromAddon(addon, normalizedType, idCandidates, videoId, options))
    );
    return allResults.flat();
  }
  async fetchSubtitlesFromAddon(addon, type, idCandidates = [], videoId, options = {}) {
    const candidateIds = this.uniqueNonEmpty(
      (addon.resources || []).flatMap((resource) =>
        this.isSubtitleResource(resource?.name) && this.supportsType(resource, type)
          ? buildSubtitleIdCandidates({
              ...options,
              type,
              ids: idCandidates,
              videoId,
              idPrefixes: addonRepository.getResourceIdPrefixes(addon, resource)
            })
          : []
      )
    );
    const results = await Promise.all(candidateIds.map(async (id) => {
      const fetchTracks = async (url, timeoutMs = PER_ADDON_TIMEOUT_MS) => {
        const result = await this.withTimeout(safeApiCall(() => SubtitleApi.getSubtitles(url)), timeoutMs);
        const tracks = result?.status === "success" ? result.data?.subtitles : null;
        return Array.isArray(tracks) ? tracks.filter((track) => typeof track?.url === "string" && track.url.trim()) : [];
      };
      const baseUrl = this.buildSubtitlesUrl(addon.baseUrl, type, id);
      const releaseUrl = this.buildSubtitlesUrl(addon.baseUrl, type, id, options);
      if (releaseUrl === baseUrl) return fetchTracks(baseUrl);
      const matched = await fetchTracks(releaseUrl, RELEASE_LOOKUP_TIMEOUT_MS);
      return matched.length ? matched : fetchTracks(baseUrl);
    }));
    const seen = new Set();
    return results.flat().flatMap((subtitle) => {
      const lang = subtitle.lang || subtitle.language || subtitle.languageCode || "unknown";
      const key = JSON.stringify([subtitle.url, lang]);
      if (seen.has(key)) return [];
      seen.add(key);
      const headers = subtitle.headers || subtitle.behaviorHints?.proxyHeaders?.request;
      return [{
        id: subtitle.id || lang + "-" + this.makeDeterministicId(subtitle.url),
        url: subtitle.url,
        lang,
        ...(headers ? { headers } : {}),
        addonName: addon.displayName,
        addonLogo: addon.logo
      }];
    });
  }
  isSubtitleResource(name) {
    const resourceName = String(name || "").toLowerCase();
    return resourceName === "subtitles" || resourceName === "subtitle";
  }
  canonicalSubtitleType(type) {
    const normalized = String(type || "")
      .trim()
      .toLowerCase();
    return normalized === "tv" ? "series" : normalized;
  }
  supportsType(resource, type) {
    const types = addonRepository.getResourceTypes(resource).map((value) => this.canonicalSubtitleType(value));
    return !types.length || types.includes(type);
  }
  uniqueNonEmpty(values = []) {
    const unique = [];
    const seen = new Set();
    (values || []).forEach((value) => {
      const normalized = String(value || "").trim();
      if (!normalized || seen.has(normalized)) {
        return;
      }
      seen.add(normalized);
      unique.push(normalized);
    });
    return unique;
  }
  async resolveCanonicalId(type, options = {}) {
    const tmdbMatch = options.tmdbId ? await TmdbService.tmdbToImdb(options.tmdbId, type) : null;
    if (tmdbMatch) return tmdbMatch;
    const title = String(options?.title || "").trim();
    const year = releaseYear(options?.year);
    if (!["movie", "series"].includes(type) || !title || !year) {
      return "";
    }
    const result = await catalogRepository.getCatalog({
      addonBaseUrl: CINEMETA_BASE_URL,
      addonId: "org.cinemeta",
      addonName: "Cinemeta",
      catalogId: "top",
      catalogName: "Cinemeta Search",
      type,
      extraArgs: { search: title },
      supportsSkip: false
    });
    if (result?.status !== "success") {
      return "";
    }
    return selectCanonicalCinemetaId(result.data?.items || [], { type, title, year });
  }
  async withTimeout(promise, timeoutMs) {
    let timeoutId = null;
    try {
      const timeoutPromise = new Promise((resolve) => {
        timeoutId = setTimeout(
          () => {
            resolve({ status: "timeout" });
          },
          Math.max(500, Number(timeoutMs || 0))
        );
      });
      return await Promise.race([promise, timeoutPromise]);
    } finally {
      if (timeoutId) {
        clearTimeout(timeoutId);
      }
    }
  }
  buildSubtitlesUrl(baseUrl, type, id, options = {}) {
    const cleanBaseUrl = addonRepository.canonicalizeUrl(baseUrl);
    const queryStart = cleanBaseUrl.indexOf("?");
    const basePath =
      queryStart >= 0 ? cleanBaseUrl.slice(0, queryStart).replace(/\/+$/, "") : cleanBaseUrl;
    const baseQuery = queryStart >= 0 ? cleanBaseUrl.slice(queryStart) : "";
    const extraParams = this.buildExtraParams(options);
    const suffix = extraParams ? `/${extraParams}` : "";
    return `${basePath}/subtitles/${this.encode(type)}/${this.encodeSubtitleId(id)}${suffix}.json${baseQuery}`;
  }
  encode(value) {
    return encodeURIComponent(String(value || "")).replace(/\+/g, "%20");
  }
  encodeSubtitleId(value) {
    return encodeURIComponent(String(value || ""))
      .replace(/\+/g, "%20")
      .replace(/%3A/gi, ":");
  }
  makeDeterministicId(value) {
    let hash = 0;
    const str = String(value || "");
    for (let index = 0; index < str.length; index += 1) {
      hash = (hash << 5) - hash + str.charCodeAt(index);
      hash |= 0;
    }
    return Math.abs(hash);
  }
  buildExtraParams(options = {}) {
    const params = [];
    const push = (key, value) => {
      const normalized = String(value ?? "").trim();
      if (!normalized) {
        return;
      }
      params.push(`${key}=${encodeURIComponent(normalized).replace(/\+/g, "%20")}`);
    };
    push("videoHash", options.videoHash);
    const videoSize = Number(options.videoSize || 0);
    if (Number.isFinite(videoSize) && videoSize > 0) {
      push("videoSize", Math.trunc(videoSize));
    }
    push("filename", options.filename);
    return params.join("&");
  }
}
export const subtitleRepository = new SubtitleRepository();
