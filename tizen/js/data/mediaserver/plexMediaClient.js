/**
 * Plex Media Server compatibility adapter for Starlit's native TV catalog and
 * playback pipeline. Never sends the user's token to an off-origin URL.
 * Plex's native JSON API is mapped to the existing read-only MediaBrowser shape.
 */
import { LocalStore } from "../../core/storage/localStore.js";
const PRODUCT = "Starlit TV";
const CLIENT_ID_KEY = "starlit-plex-device-id-v1";
const MAX_BODY = 8 * 1024 * 1024;

function deviceId() {
  let id = LocalStore.get(CLIENT_ID_KEY, null);
  if (typeof id === "string" && /^[a-f0-9]{32}$/.test(id)) return id;
  const b = new Uint8Array(16);
  globalThis.crypto?.getRandomValues?.(b);
  id = Array.from(b, byte => byte.toString(16).padStart(2, "0")).join("");
  if (!/^[a-f0-9]{32}$/.test(id)) throw new Error("Plex client identity is unavailable");
  LocalStore.set(CLIENT_ID_KEY, id);
  return id;
}
const segment = value => encodeURIComponent(String(value));
function safeToken(token) {
  if (typeof token !== "string" || !token || /[\r\n]/.test(token)) throw new Error("servers_error_auth");
  return token;
}
export function plexAuthorizedUrl(connection, resource, query = {}) {
  const origin = new URL(connection.address);
  const url = new URL(resource, origin.toString().replace(/\/+$/, "") + "/");
  if (!["http:", "https:"].includes(url.protocol) || url.origin !== origin.origin)
    throw new Error("servers_error_failed");
  Object.entries(query).forEach(([key, value]) => {
    if (value != null) url.searchParams.set(key, String(value));
  });
  url.searchParams.set("X-Plex-Token", safeToken(connection.token));
  return url.toString();
}

export async function plexRequest(connection, resource, {
  method = "GET", query = {}, signal, body = null, allowEmpty = false
} = {}) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 15000);
  const abort = () => controller.abort();
  signal?.addEventListener?.("abort", abort, { once: true });
  if (signal?.aborted) controller.abort();
  try {
    const endpoint = plexAuthorizedUrl(connection, resource, query);
    const response = await fetch(endpoint, {
      method, redirect: "error", signal: controller.signal,
      headers: {
        Accept: "application/json",
        "X-Plex-Token": safeToken(connection.token),
        "X-Plex-Client-Identifier": deviceId(),
        "X-Plex-Product": PRODUCT,
        "X-Plex-Version": "1.0",
        ...(body == null ? {} : { "Content-Type": "application/json" })
      },
      body: body == null ? undefined : JSON.stringify(body)
    });
    if (!response.ok) {
      const error = new Error(response.status === 401 ? "servers_error_auth" :
        response.status === 403 ? "servers_error_forbidden" : "servers_error_failed");
      error.status = response.status;
      throw error;
    }
    if (response.status === 204 || allowEmpty) return null;
    const size = Number(response.headers?.get?.("content-length") || 0);
    if (size > MAX_BODY) throw new Error("servers_error_failed");
    const text = await response.text();
    if (text.length > MAX_BODY) throw new Error("servers_error_failed");
    return text ? JSON.parse(text) : null;
  } finally {
    clearTimeout(timer);
    signal?.removeEventListener?.("abort", abort);
  }
}

function container(data) {
  return data?.MediaContainer && typeof data.MediaContainer === "object" ? data.MediaContainer : {};
}
const array = data => Array.isArray(data) ? data : data ? [data] : [];
const plexTicks = milliseconds => Math.max(0, Number(milliseconds || 0)) * 10000;
const userData = item => ({
  Played: Number(item.viewCount || 0) > 0,
  PlaybackPositionTicks: plexTicks(item.viewOffset),
  LastPlayedDate: Number(item.lastViewedAt || 0) > 0
    ? new Date(Number(item.lastViewedAt) * 1000).toISOString() : undefined
});
function externalIds(item) {
  const ids = {};
  for (const value of array(item.Guid)) {
    const match = String(value?.id || value || "").match(/^(imdb|tmdb):\/\/([a-z0-9]+)/i);
    if (!match) continue;
    ids[match[1].toLowerCase() === "imdb" ? "Imdb" : "Tmdb"] = match[2];
  }
  return ids;
}
function sourceParts(connection, item) {
  return array(item.Media).flatMap((media, mediaIndex) =>
    array(media.Part).filter(part => part?.key).map((part, partIndex) => ({
      Id: String(part.id || `${item.ratingKey}-${mediaIndex}-${partIndex}`),
      Name: part.file?.split(/[\\/]/).pop() || media.container || "Plex",
      Path: part.file || "",
      Container: media.container || part.container || "",
      Size: Number(part.size || 0),
      SupportsDirectPlay: true,
      SupportsDirectStream: true,
      PlexDirectUrl: plexAuthorizedUrl(connection, part.key, { download: 1 }),
      MediaStreams: array(part.Stream).map(stream => {
        const streamType = Number(stream.streamType || 0);
        const kind = streamType === 1 ? "Video" : streamType === 2 ? "Audio" : "Subtitle";
        return {
          Index: Number(stream.index ?? stream.id ?? 0),
          Type: kind,
          Language: stream.languageCode || "und",
          DisplayTitle: stream.displayTitle || stream.title || "",
          VideoCodec: stream.codec || media.videoCodec,
          AudioCodec: stream.codec || media.audioCodec,
          DeliveryMethod: streamType === 3 && stream.key ? "External" : "Embed",
          DeliveryUrl: streamType === 3 ? stream.key : undefined
        };
      })
    }))
  );
}
export function mapPlexMetadata(connection, item) {
  if (!item?.ratingKey) return null;
  const type = String(item.type || "").toLowerCase();
  const standard = type === "movie" ? "Movie" : type === "show" ? "Series" :
    type === "episode" ? "Episode" : type === "collection" ? "BoxSet" : null;
  if (!standard) return null;
  const sources = sourceParts(connection, item);
  return {
    Id: String(item.ratingKey),
    Name: item.title || "",
    Overview: item.summary || "",
    Type: standard,
    CollectionType: type === "show" ? "tvshows" : type === "movie" ? "movies" : type,
    Genres: array(item.Genre).map(genre => genre?.tag).filter(Boolean),
    ProductionYear: Number(item.year || 0) || undefined,
    PremiereDate: item.originallyAvailableAt,
    ParentIndexNumber: item.parentIndex == null ? null : Number(item.parentIndex),
    IndexNumber: item.index == null ? null : Number(item.index),
    ProviderIds: externalIds(item),
    CommunityRating: Number(item.audienceRating ?? item.rating ?? 0) || undefined,
    RunTimeTicks: plexTicks(item.duration),
    PlexPoster: item.thumb,
    PlexBackdrop: item.art,
    PlexLogo: item.clearLogo,
    MediaSources: sources,
    UserData: userData(item)
  };
}
function plexItems(connection, data) {
  const value = container(data);
  const entries = array(value.Metadata).length ? array(value.Metadata) :
    array(value.Hub).flatMap(h => array(h.Metadata));
  const items = entries.map(item => mapPlexMetadata(connection, item)).filter(Boolean);
  return {
    Items: items,
    TotalRecordCount: Number(value.totalSize ?? value.size ?? items.length),
    MediaSources: items.flatMap(item => item.MediaSources)
  };
}
const knownPath = path => String(path || "").replace(/\/+$/, "");
const idFromPath = (path, prefix) => path.slice(prefix.length).split("/")[0];

export async function plexCompatRequest(connection, rawPath, options = {}) {
  const path = knownPath(rawPath);
  const query = options.query || {};
  if (path === "/System/Info/Public" || path === "/identity") {
    const data = container(await plexRequest(connection, "/identity", options));
    return { Id: data.machineIdentifier, ServerName: data.friendlyName || "Plex", ProductName: "Plex" };
  }
  if (path === "/UserViews") {
    const sections = container(await plexRequest(connection, "/library/sections", options));
    return { Items: array(sections.Directory).filter(section =>
      section?.type === "movie" || section?.type === "show").map(section => ({
        Id: String(section.key), Name: section.title || "Library",
        CollectionType: section.type === "show" ? "tvshows" : "movies"
      })) };
  }
  if (path === "/Items" || path === "/UserItems/Resume") {
    const parentId = String(query.parentId || "");
    const resume = path === "/UserItems/Resume";
    const route = resume ? "/hubs/home/continueWatching" :
      parentId && query.recursive === false
        ? `/library/collections/${segment(parentId)}/children`
        : `/library/sections/${segment(parentId)}/all`;
    if (!resume && !parentId) return { Items: [], TotalRecordCount: 0 };
    const parameters = {
      "X-Plex-Container-Start": Math.max(0, Number(query.startIndex || 0)),
      "X-Plex-Container-Size": Math.max(1, Math.min(200, Number(query.limit || 100))),
      includeGuids: 1,
      ...(query.searchTerm ? { title: String(query.searchTerm) } : {}),
      ...(query.includeItemTypes === "Movie" ? { type: 1 } : {}),
      ...(query.includeItemTypes === "Series" ? { type: 2 } : {})
    };
    let result = plexItems(connection, await plexRequest(connection, route, { ...options, query: parameters }));
    if (query.searchTerm) {
      const name = query.searchTerm.toLowerCase();
      result.Items = result.Items.filter(item => item.Name.toLowerCase().includes(name));
    }
    if (query.anyProviderIdEquals) {
      const [provider, ...value] = String(query.anyProviderIdEquals).split(".");
      const target = value.join(".");
      result.Items = result.Items.filter(item => String(item.ProviderIds?.[provider] || "") === target);
    }
    return result;
  }
  if (/^\/Items\/[^/]+\/PlaybackInfo$/.test(path)) {
    const itemId = idFromPath(path, "/Items/");
    const item = await plexCompatRequest(connection, "/Items/" + segment(itemId), options);
    if (!item) throw new Error("servers_error_failed");
    const baseQuery = {
      path: "/library/metadata/" + itemId,
      protocol: "hls",
      directPlay: 0,
      directStream: 1,
      directStreamAudio: 1,
      fastSeek: 1,
      session: Date.now().toString(36),
      maxVideoBitrate: "120000"
    };
    return {
      PlaySessionId: baseQuery.session,
      MediaSources: (item.MediaSources || []).map(source => ({
        ...source,
        SupportsDirectPlay: options.query?.enableDirectPlay !== false,
        TranscodingUrl: plexAuthorizedUrl(connection, "/video/:/transcode/universal/start.m3u8", baseQuery)
      }))
    };
  }
  if (/^\/Items\/[^/]+$/.test(path)) {
    const id = idFromPath(path, "/Items/");
    return plexItems(connection, await plexRequest(connection, "/library/metadata/" + segment(id),
      { ...options, query: { includeGuids: 1 } })).Items[0] || null;
  }
  if (/^\/Shows\/[^/]+\/Episodes$/.test(path)) {
    const id = idFromPath(path, "/Shows/");
    return plexItems(connection, await plexRequest(connection,
      "/library/metadata/" + segment(id) + "/allLeaves",
      { ...options, query: { includeGuids: 1 } }));
  }
  if (path === "/Genres") return { Items: [] };
  if (/^\/UserPlayedItems\/[^/]+$/.test(path)) {
    const id = idFromPath(path, "/UserPlayedItems/");
    const state = options.method === "DELETE" ? "unscrobble" : "scrobble";
    return plexRequest(connection, "/:/"+state, {
      ...options, method: "GET",
      query: { key: id, identifier: "com.plexapp.plugins.library" },
      allowEmpty: true
    });
  }
  if (path.startsWith("/Sessions/Playing")) {
    const body = options.body || {};
    const state = /Stopped$/.test(path) ? "stopped" : body.IsPaused ? "paused" : "playing";
    return plexRequest(connection, "/:/timeline", {
      ...options, method: "GET",
      query: {
        ratingKey: body.ItemId,
        key: "/library/metadata/" + body.ItemId,
        state,
        time: Math.round(Number(body.PositionTicks || 0) / 10000),
        hasMDE: "1"
      },
      allowEmpty: true
    });
  }
  if (path === "/Sessions/Logout") return null;
  throw new Error("servers_error_unsupported");
}
