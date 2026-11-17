// A torrent create response only confirms metadata. AVPlay needs actual verified
// media bytes; the first range often blocks while its pieces are downloaded.
const DEFAULT_WAIT_MS = 35_000;
const DEFAULT_PROBE_MS = 35_000;
const DEFAULT_RETRY_MS = 750;

export function isLocalTizenTorrentPlaybackUrl(value) {
  try {
    const parsed = new URL(String(value || ""));
    const host = parsed.hostname.replace(/^\[|\]$/g, "").toLowerCase();
    return parsed.protocol === "http:" && parsed.port === "2710" &&
      ["127.0.0.1", "localhost", "::1"].includes(host) &&
      /^\/[a-f0-9]{40}\/\d+\/?$/i.test(parsed.pathname);
  } catch (_) {
    return false;
  }
}

// XHR, unlike fetch on Chromium 63, supports bounded cancellation without AbortController.
// Only accept a 206 containing the requested byte; HEAD/metadata/empty 206 are not ready.
export function probeTizenTorrentFirstByte(url, timeoutMs = DEFAULT_PROBE_MS, createRequest = () => new XMLHttpRequest()) {
  return new Promise((resolve) => {
    let xhr;
    let watchdog;
    let settled = false;
    const finish = (result) => {
      if (settled) return;
      settled = true;
      if (watchdog) clearTimeout(watchdog);
      if (xhr) xhr.onload = xhr.onerror = xhr.ontimeout = xhr.onabort = null;
      resolve(result);
    };
    try {
      xhr = createRequest();
      xhr.onload = () => {
        const status = Number(xhr.status);
        if (status === 206 && Number(xhr.response?.byteLength) > 0) return finish("ready");
        finish([401, 403, 416].includes(status) ? "terminal" : "retry");
      };
      xhr.onerror = xhr.ontimeout = xhr.onabort = () => finish("retry");
      xhr.open("GET", url, true);
      xhr.responseType = "arraybuffer";
      xhr.timeout = timeoutMs;
      xhr.setRequestHeader("Range", "bytes=0-0");
      watchdog = setTimeout(() => {
        try { xhr.abort(); } catch (_) {}
        finish("retry");
      }, timeoutMs + 250);
      xhr.send();
    } catch (_) {
      finish("retry");
    }
  });
}

export async function waitForTizenTorrentFirstByte(url, {
  timeoutMs = DEFAULT_WAIT_MS,
  probeTimeoutMs = DEFAULT_PROBE_MS,
  retryMs = DEFAULT_RETRY_MS,
  probe = probeTizenTorrentFirstByte,
  isCurrent = () => true
} = {}) {
  if (!isLocalTizenTorrentPlaybackUrl(url)) return false;
  const deadline = Date.now() + Math.max(1, timeoutMs);
  while (Date.now() < deadline) {
    if (!isCurrent()) return false;
    const remaining = deadline - Date.now();
    let result = "retry";
    try {
      result = await probe(url, Math.min(probeTimeoutMs, Math.max(1, remaining)));
    } catch (_) {
      // Temporary local service or connection errors must not discard the swarm.
    }
    if (!isCurrent()) return false;
    if (result === "ready") return true;
    if (result === "terminal") return false;
    const wait = Math.min(retryMs, deadline - Date.now());
    if (wait > 0) await new Promise((resolve) => setTimeout(resolve, wait));
  }
  return false;
}
