import assert from "node:assert/strict";
import test, { afterEach } from "node:test";
import { plexAuthorizedUrl, mapPlexMetadata, plexCompatRequest } from "../js/data/mediaserver/plexMediaClient.js";
import { parseSyncedServers, toSyncPayload } from "../js/data/mediaserver/mediaServerSyncModel.js";

const connection = {
  id: "plex-1", providerId: "plex", address: "http://192.168.0.50:32400",
  token: "secret-plex-token", remoteUserId: "plex-user"
};
const sample = {
  ratingKey: "1001", type: "movie", title: "Example Film", summary: "Example", year: 2024,
  duration: 108000, viewOffset: 12000, viewCount: 1, lastViewedAt: 1600000000,
  thumb: "/library/metadata/1001/thumb/7", art: "/library/metadata/1001/art/7",
  Guid: [{ id: "imdb://tt1234567" }, { id: "tmdb://1234" }],
  Media: [{ id: 5, container: "mkv", videoCodec: "h264", Part: [{
    id: 42, key: "/library/parts/42/file.mkv", file: "/Movies/Example.mkv", size: 1234,
    Stream: [{ streamType: 1, id: 1, codec: "h264" }, { streamType: 2, id: 2, codec: "aac" }]
  }] }]
};
let savedFetch = globalThis.fetch;
afterEach(() => { globalThis.fetch = savedFetch; });

test("Plex asset URL never leaks tokens to another origin", () => {
  const valid = plexAuthorizedUrl(connection, "/library/parts/42/file", { download: 1 });
  assert.match(valid, /X-Plex-Token=secret-plex-token/);
  assert.match(valid, /^http:\/\/192\.168\.0\.50:32400/);
  assert.throws(() => plexAuthorizedUrl(connection, "https://evil.example/collect"), /servers_error_failed/);
  assert.throws(() => plexAuthorizedUrl(connection, "//evil.example/collect"), /servers_error_failed/);
  assert.throws(() => plexAuthorizedUrl({ ...connection, token: "x\r\ny" }, "/image"), /servers_error_auth/);
});

test("Plex metadata maps to native Starlit item and direct source", () => {
  const item = mapPlexMetadata(connection, sample);
  assert.equal(item.Id, "1001");
  assert.equal(item.Type, "Movie");
  assert.equal(item.ProviderIds.Imdb, "tt1234567");
  assert.equal(item.ProviderIds.Tmdb, "1234");
  assert.equal(item.UserData.Played, true);
  assert.equal(item.UserData.PlaybackPositionTicks, 120000000);
  assert.equal(item.MediaSources[0].Id, "42");
  assert.match(item.MediaSources[0].PlexDirectUrl, /X-Plex-Token=/);
  assert.equal(mapPlexMetadata(connection, { ratingKey: "2", type: "artist" }), null);
});

test("Plex section/catalog/details and episode routes are converted to MediaBrowser results", async () => {
  const requests = [];
  globalThis.fetch = async (url, opts) => {
    requests.push({ url: String(url), headers: opts.headers });
    let payload;
    if (String(url).includes("/identity")) payload = { MediaContainer: { machineIdentifier: "srv-1", friendlyName: "Home Plex" } };
    else if (String(url).includes("/library/sections?")) payload = { MediaContainer: { Directory: [{ key: 1, title: "Movies", type: "movie" }, { key: 2, title: "Shows", type: "show" }] } };
    else if (String(url).includes("/allLeaves")) payload = { MediaContainer: { Metadata: [{ ratingKey: "1002", title: "First", type: "episode", parentIndex: 1, index: 1 }] } };
    else payload = { MediaContainer: { Metadata: [sample], totalSize: 1 } };
    return { ok: true, status: 200, headers: { get: () => null }, text: async () => JSON.stringify(payload) };
  };
  const info = await plexCompatRequest(connection, "/System/Info/Public");
  assert.equal(info.Id, "srv-1");
  const libraries = await plexCompatRequest(connection, "/UserViews");
  assert.equal(libraries.Items[0].CollectionType, "movies");
  const listing = await plexCompatRequest(connection, "/Items", { query: { parentId: "1", recursive: true, limit: 20 } });
  assert.equal(listing.Items[0].Id, "1001");
  assert.equal(listing.TotalRecordCount, 1);
  const details = await plexCompatRequest(connection, "/Items/1001");
  assert.equal(details.Type, "Movie");
  const episodes = await plexCompatRequest(connection, "/Shows/2001/Episodes");
  assert.equal(episodes.Items[0].IndexNumber, 1);
  const playback = await plexCompatRequest(connection, "/Items/1001/PlaybackInfo", { method: "POST", query: { enableDirectPlay: false } });
  assert.equal(playback.MediaSources[0].SupportsDirectPlay, false);
  assert.match(playback.MediaSources[0].TranscodingUrl, /start\.m3u8/);
  assert.ok(requests.every(x => x.headers["X-Plex-Token"] === connection.token));
  assert.ok(requests.every(x => x.url.startsWith(connection.address + "/")));
});

test("Plex watch sync imports known provider but retains unknown Silo servers", () => {
  const base = {
    id: "p1", provider_id: "plex", name: "Plex", address: connection.address,
    remote_server_id: "srv", remote_user_id: "plex-user",
    user_name: "Plex", token: connection.token,
    libraries: [{ id: "1", name: "Movies", kind: "movie", selected: true }],
    enabled: true
  };
  const unknown = { id: "silo-1", provider_id: "silo", server: "private" };
  const parsed = parseSyncedServers([{ servers_json: [base, unknown] }]);
  assert.equal(parsed.servers.length, 1);
  assert.equal(parsed.preserved.length, 1);
  assert.deepEqual(toSyncPayload(parsed.servers, parsed.preserved)[1], unknown);
});
