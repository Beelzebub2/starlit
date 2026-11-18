import assert from "node:assert/strict";
import { test } from "node:test";

globalThis.localStorage ??= { getItem: () => null, setItem: () => {}, removeItem: () => {} };
const { addonRepository } = await import("../js/data/repository/addonRepository.js");
const { SubtitleApi } = await import("../js/data/remote/api/subtitleApi.js");
const { subtitleRepository } = await import("../js/data/repository/subtitleRepository.js");
const { skipIntroRepository } = await import("../js/data/repository/skipIntroRepository.js");
const { animeIdResolver } = await import("../js/data/repository/animeIdResolver.js");

test("resolves canonical episode subtitles and retries a release request without file hints", async (t) => {
  t.mock.method(addonRepository, "getInstalledAddons", async () => [{
    baseUrl: "https://example.test?config=yes",
    displayName: "Fixture",
    idPrefixes: ["tmdb:"],
    resources: [{ name: "subtitles", types: ["tv"], idPrefixes: ["tt"] }]
  }]);
  const urls = [];
  t.mock.method(SubtitleApi, "getSubtitles", async (url) => {
    urls.push(url);
    if (url.includes("filename=")) throw new Error("Title-only endpoint");
    return { subtitles: [null, { url: "https://media.test/en.vtt", language: "eng" }] };
  });
  const subtitles = await subtitleRepository.getSubtitles("series", "tmdb:51", "opaque-episode", {
    season: 0, episode: 2, imdbId: "tt504", filename: "Release & Name.mkv"
  });
  assert.deepEqual(urls, [
    "https://example.test/subtitles/series/tt504:0:2/filename=Release%20%26%20Name.mkv.json?config=yes",
    "https://example.test/subtitles/series/tt504:0:2.json?config=yes"
  ]);
  assert.deepEqual(subtitles.map(({ url, lang }) => [url, lang]), [["https://media.test/en.vtt", "eng"]]);
});

test("recovers movie and episode markers through the public fallback", async (t) => {
  t.mock.method(animeIdResolver, "resolveAnimeIds", async () => null);
  t.mock.method(globalThis, "fetch", async (url) => {
    const parsed = new URL(url);
    if (parsed.hostname !== "api.theintrodb.org") return new Response(null, { status: 503 });
    return Response.json(parsed.searchParams.has("episode") ? {
      recap: [{ start_ms: null, end_ms: 25000 }],
      credits: [{ start_ms: 1400000, end_ms: 1450000 }]
    } : {
      intro: [{ start_ms: null, end_ms: 84000 }],
      credits: [{ start_ms: 6000000, end_ms: null }]
    });
  });
  assert.deepEqual((await skipIntroRepository.getMovieSkipIntervals({ imdbId: "tt501" })).map(({ type }) => type),
    ["intro"]);
  assert.deepEqual((await skipIntroRepository.getSkipIntervals("tt501", 0, 1)).map(({ type }) => type),
    ["recap", "outro"]);
});

test("keeps two anime episodes without IMDb IDs in separate skip caches", async (t) => {
  t.mock.method(animeIdResolver, "resolveAnimeIds", async ({ contentId }) => ({ mal: contentId.split(":")[1] }));
  t.mock.method(globalThis, "fetch", async (url) => {
    const match = new URL(url).pathname.match(/skip-times\/(\d+)\/1$/);
    if (!match) return new Response(null, { status: 404 });
    const startTime = Number(match[1]);
    return Response.json({ found: true, results: [{
      skipType: "op", interval: { startTime, endTime: startTime + 90 }
    }] });
  });
  assert.equal((await skipIntroRepository.getSkipIntervals("", 1, 1, { contentId: "mal:2" }))[0].startTime, 2);
  assert.equal((await skipIntroRepository.getSkipIntervals("", 1, 1, { contentId: "mal:7" }))[0].startTime, 7);
});
