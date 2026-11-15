import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import vm from "node:vm";
// The lazy loader reaches the worker pool and the session lifecycle through
// imports, so the real hydration/release code is evaluated against stubs. The
// blob ownership rules are what these tests guard: a stale or released url
// must never be painted, and a live one must be handed back exactly once.
const source = (await readFile(new URL("./tvArtworkLazyLoader.js", import.meta.url), "utf8"))
  .replace(/^import [\s\S]*?";$/m, "")
  .replace(/^import [^;]+;$/gm, "")
  .replace(/^export /gm, "");
const TRANSPARENT_PIXEL = "data:image/gif;base64,R0lGODlhAQABAAD/ACwAAAAAAQABAAACADs=";
function buildContext({ rescaler = true, constrained = false } = {}) {
  const pending = [];
  const released = [];
  const context = vm.createContext({
    console,
    URL,
    setTimeout,
    clearTimeout,
    getHomeArtworkObjectFit: () => "cover",
    isRescalableArtworkFit: (fit) => fit === "cover" || fit === "fill",
    releaseArtwork: (url) => released.push(url),
    rescaleArtwork: (url, options) =>
      new Promise((resolve) => {
        pending.push({ url, options, resolve });
      }),
    resetArtworkRescaler: () => {},
    shouldRescaleHomeArtwork: () => rescaler,
    getTvRuntimePerformanceProfile: () => ({ isTvRuntime: true, isPerformanceConstrained: constrained }),
    getTvArtworkPixelRatio: () => 1,
    normalizeTmdbPosterUrl: (url) => url.replace("/t/p/original/", "/t/p/w342/"),
    registerSessionTeardownHandler: () => {}
  });
  context.globalThis = context;
  vm.runInContext(
    `${source}\nglobalThis.api = { hydrate, releaseImage, setArtworkSource, prepareImage, startTvArtworkLazyLoader, stopTvArtworkLazyLoader };`,
    context
  );
  return { context, pending, released };
}
function makeImage(source) {
  const attributes = new Map([["src", source]]);
  return {
    nodeType: 1,
    tagName: "IMG",
    dataset: { artworkSrc: source },
    isConnected: true,
    classList: { contains: () => false },
    closest: () => null,
    matches: () => true,
    getAttribute: (name) => (attributes.has(name) ? attributes.get(name) : null),
    hasAttribute: (name) => attributes.has(name),
    setAttribute: (name, value) => attributes.set(name, value),
    removeAttribute: (name) => attributes.delete(name),
    getBoundingClientRect: () => ({
      top: 0,
      bottom: 338,
      left: 0,
      right: 220,
      width: 220,
      height: 338
    }),
    get src() {
      return attributes.get("src") || "";
    },
    set src(value) {
      attributes.set("src", value);
    }
  };
}
const flush = () => new Promise((resolve) => setTimeout(resolve, 0));
test("a live rescale paints the blob and records its ownership", async () => {
  const { context, pending } = buildContext();
  const image = makeImage("https://image.tmdb.org/t/p/w500/a.jpg");
  context.api.prepareImage(image);
  assert.equal(pending.length, 1);
  pending[0].resolve("blob:live");
  await flush();
  assert.equal(image.src, "blob:live");
  assert.equal(image.dataset.artworkObjectUrl, "blob:live");
  assert.equal(image.dataset.artworkPending, undefined);
});
test("a rescale that finishes after the card is gone releases its blob", async () => {
  const { context, pending, released } = buildContext();
  const image = makeImage("https://image.tmdb.org/t/p/w500/b.jpg");
  context.api.prepareImage(image);
  image.isConnected = false;
  pending[0].resolve("blob:stale");
  await flush();
  assert.deepEqual(released, ["blob:stale"]);
  assert.equal(image.src, TRANSPARENT_PIXEL, "the stale url must never be painted");
  assert.equal(image.dataset.artworkObjectUrl, undefined);
});
test("a rescale that finishes after the source changed releases its blob", async () => {
  const { context, pending, released } = buildContext();
  const image = makeImage("https://image.tmdb.org/t/p/w500/c.jpg");
  context.api.prepareImage(image);
  image.dataset.artworkSrc = "https://image.tmdb.org/t/p/w500/new.jpg";
  pending[0].resolve("blob:old");
  await flush();
  assert.deepEqual(released, ["blob:old"]);
  assert.equal(image.dataset.artworkObjectUrl, undefined);
});
test("releasing a painted image hands the blob back exactly once", () => {
  const { context, released } = buildContext();
  const image = makeImage("https://image.tmdb.org/t/p/w500/d.jpg");
  context.api.setArtworkSource(image, "blob:painted");
  context.api.releaseImage(image);
  context.api.releaseImage(image);
  assert.deepEqual(released, ["blob:painted"]);
  assert.equal(image.dataset.artworkObjectUrl, undefined);
});
test("Tizen 5 without OffscreenCanvas defers non-Home posters until visible", () => {
  const { context, pending } = buildContext({ rescaler: false });
  const sourceUrl = "https://image.tmdb.org/t/p/w500/offscreen.jpg";
  const image = makeImage(sourceUrl);
  image.getBoundingClientRect = () => ({
    top: 1500,
    bottom: 1838,
    left: 0,
    right: 220,
    width: 220,
    height: 338
  });
  const observed = [];
  context.innerWidth = 1920;
  context.innerHeight = 1080;
  context.IntersectionObserver = class {
    constructor(callback) {
      this.callback = callback;
      context.artworkObserver = this;
    }
    observe(node) { observed.push(node); }
    unobserve(node) { observed.splice(observed.indexOf(node), 1); }
    disconnect() { observed.length = 0; }
  };
  context.MutationObserver = class {
    observe() {}
    disconnect() {}
  };
  context.document = {
    body: {
      nodeType: 1,
      querySelectorAll: () => [image],
      closest: () => null
    }
  };
  assert.equal(context.api.startTvArtworkLazyLoader(), true);
  assert.equal(image.src, TRANSPARENT_PIXEL, "offscreen poster must not decode at mount");
  assert.equal(observed.length, 1);
  assert.equal(pending.length, 0, "no worker/canvas work on legacy Tizen");
  context.artworkObserver.callback([{ target: image, isIntersecting: true }]);
  assert.equal(image.src, sourceUrl);
  assert.equal(image.dataset.artworkPending, undefined);
  assert.equal(pending.length, 0);
  context.api.stopTvArtworkLazyLoader();
});
test("Tizen 5 without OffscreenCanvas hydrates a visible poster immediately", () => {
  const { context, pending } = buildContext({ rescaler: false });
  const sourceUrl = "https://image.tmdb.org/t/p/w500/visible.jpg";
  const image = makeImage(sourceUrl);
  context.innerWidth = 1920;
  context.innerHeight = 1080;
  context.IntersectionObserver = class {
    observe() {}
    unobserve() {}
    disconnect() {}
  };
  context.document = {
    body: {
      nodeType: 1,
      querySelectorAll: () => [image],
      closest: () => null
    }
  };
  assert.equal(context.api.startTvArtworkLazyLoader(), true);
  assert.equal(image.src, sourceUrl, "visible poster must not wait for an observer callback");
  assert.equal(image.dataset.artworkPending, undefined);
  assert.equal(pending.length, 0);
  context.api.stopTvArtworkLazyLoader();
});
test("TV without an image worker or IntersectionObserver keeps the native image path", () => {
  const { context } = buildContext({ rescaler: false });
  context.document = { body: { nodeType: 1 } };
  assert.equal(context.api.startTvArtworkLazyLoader(), false);
});


test("constrained TV hydrates third-party posters only after visibility", () => {
  const { context, pending } = buildContext({ rescaler: false, constrained: true });
  const sourceUrl = "https://addon.example/assets/poster.jpg";
  const image = makeImage(sourceUrl);
  image.getBoundingClientRect = () => ({ top: 2000, bottom: 2338, left: 0, right: 220, width: 220, height: 338 });
  const frames = [];
  context.requestAnimationFrame = (callback) => { frames.push(callback); return frames.length; };
  context.cancelAnimationFrame = () => {};
  context.innerWidth = 1920;
  context.innerHeight = 1080;
  context.IntersectionObserver = class {
    constructor(callback) { context.artworkObserver = this; this.callback = callback; }
    observe() {}
    unobserve() {}
    disconnect() {}
  };
  context.MutationObserver = class { observe() {} disconnect() {} };
  context.document = { body: { nodeType: 1, querySelectorAll: () => [image], closest: () => null } };
  assert.equal(context.api.startTvArtworkLazyLoader(), true);
  assert.equal(image.src, TRANSPARENT_PIXEL);
  context.artworkObserver.callback([{ target: image, isIntersecting: true }]);
  assert.equal(image.src, TRANSPARENT_PIXEL, "decode dispatch must wait for the frame budget");
  frames.shift()();
  assert.equal(image.src, sourceUrl, "third-party image must load via native IMG");
  assert.equal(pending.length, 0, "no CORS-requiring worker path for arbitrary hosts");
  context.api.stopTvArtworkLazyLoader();
});
test("legacy poster visibility bursts are paced to five images per frame", () => {
  const { context } = buildContext({ rescaler: false, constrained: true });
  const images = Array.from({ length: 13 }, (_, index) => makeImage("https://addon.example/" + index + ".jpg"));
  images.forEach((image) => {
    image.getBoundingClientRect = () => ({ top: 2200, bottom: 2538, left: 0, right: 220, width: 220, height: 338 });
  });
  const frames = [];
  context.requestAnimationFrame = (callback) => { frames.push(callback); return frames.length; };
  context.cancelAnimationFrame = () => {};
  context.IntersectionObserver = class {
    constructor(callback) { context.artworkObserver = this; this.callback = callback; }
    observe() {}
    unobserve() {}
    disconnect() {}
  };
  context.MutationObserver = class { observe() {} disconnect() {} };
  context.document = { body: { nodeType: 1, querySelectorAll: () => images, closest: () => null } };
  assert.equal(context.api.startTvArtworkLazyLoader(), true);
  context.artworkObserver.callback(images.map((image) => ({ target: image, isIntersecting: true })));
  assert.equal(frames.length, 1);
  const hydrated = () => images.filter((image) => image.src !== TRANSPARENT_PIXEL).length;
  assert.equal(hydrated(), 0);
  frames.shift()();
  assert.equal(hydrated(), 5);
  frames.shift()();
  assert.equal(hydrated(), 10);
  frames.shift()();
  assert.equal(hydrated(), 13);
  context.api.stopTvArtworkLazyLoader();
});


test("background suspends poster work and resuming restores unpainted placeholders", () => {
  const { context } = buildContext({ rescaler: false, constrained: true });
  const image = makeImage("https://addon.example/poster.jpg");
  image.getBoundingClientRect = () => ({ top: 2000, bottom: 2338, left: 0, right: 220, width: 220, height: 338 });
  const handlers = new Map();
  const observed = [];
  const frames = [];
  let disconnects = 0;
  context.requestAnimationFrame = (callback) => { frames.push(callback); return frames.length; };
  context.cancelAnimationFrame = () => {};
  context.IntersectionObserver = class {
    constructor(callback) { this.callback = callback; context.artworkObserver = this; }
    observe(node) { observed.push(node); }
    unobserve() {}
    disconnect() { observed.length = 0; disconnects++; }
  };
  context.MutationObserver = class { observe() {} disconnect() {} };
  context.document = {
    visibilityState: "visible",
    addEventListener(event, handler) { handlers.set(event, handler); },
    removeEventListener(event) { handlers.delete(event); },
    body: { nodeType: 1, querySelectorAll: () => [image], closest: () => null }
  };
  context.innerWidth = 1920;
  context.innerHeight = 1080;
  assert.equal(context.api.startTvArtworkLazyLoader(), true);
  assert.equal(observed.length, 1);
  context.artworkObserver.callback([{ target: image, isIntersecting: true }]);
  assert.equal(frames.length, 1);
  context.document.visibilityState = "hidden";
  handlers.get("visibilitychange")();
  assert(disconnects > 0);
  assert.equal(image.src, TRANSPARENT_PIXEL);
  frames.shift()();
  assert.equal(image.src, TRANSPARENT_PIXEL, "cancelled background frame must not hydrate artwork");
  context.document.visibilityState = "visible";
  handlers.get("visibilitychange")();
  assert.equal(observed.length, 1, "unhydrated image should be observed again");
  context.artworkObserver.callback([{ target: image, isIntersecting: true }]);
  frames.shift()();
  assert.equal(image.src, "https://addon.example/poster.jpg");
  context.api.stopTvArtworkLazyLoader();
  assert.equal(handlers.size, 0);
});

test("legacy non-Home posters use a smaller TMDB decode source without changing third-party URLs", () => {
  const { context } = buildContext({ rescaler: false, constrained: true });
  const tmdb = makeImage("https://image.tmdb.org/t/p/original/oversized.jpg");
  const addon = makeImage("https://addon.example/original.jpg");
  context.api.prepareImage(tmdb);
  context.api.prepareImage(addon);
  assert.equal(tmdb.src, "https://image.tmdb.org/t/p/w342/oversized.jpg");
  assert.equal(addon.src, "https://addon.example/original.jpg");
});
