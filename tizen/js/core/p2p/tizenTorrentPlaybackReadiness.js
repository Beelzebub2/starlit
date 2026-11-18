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
export function probeTizenTorrentFirstByte(
  url, timeoutMs = DEFAULT_PROBE_MS, createRequest = () => new XMLHttpRequest(), setProbeAbort
) {
  return new Promise((resolve) => {
    let xhr;
    let watchdog;
    let settled = false;
    const finish = (result) => {
      if (settled) return;
      settled = true;
      if (watchdog) clearTimeout(watchdog);
      if (xhr) xhr.onload = xhr.onerror = xhr.ontimeout = xhr.onabort = null;
      setProbeAbort?.(null);
      resolve(result);
    };
    const abort = (result) => {
      finish(result);
      try { xhr.abort(); } catch (_) {}
    };
    try {
      xhr = createRequest();
      xhr.onload = () => {
        const status = Number(xhr.status);
        if (status === 206 && Number(xhr.response?.byteLength) === 1) return finish("ready");
        if ([401, 403, 404].includes(status)) return finish("session-lost");
        finish(status === 416 ? "unavailable" : "retry");
      };
      xhr.onerror = () => finish("disconnected");
      xhr.ontimeout = xhr.onabort = () => finish("retry");
      xhr.open("GET", url, true);
      xhr.responseType = "arraybuffer";
      xhr.timeout = timeoutMs;
      xhr.setRequestHeader("Range", "bytes=0-0");
      watchdog = setTimeout(() => abort("retry"), timeoutMs + 250);
      setProbeAbort?.(() => abort("cancelled"));
      if (!settled) xhr.send();
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
  setProbeAbort,
  isCurrent = () => true
} = {}) {
  if (!isLocalTizenTorrentPlaybackUrl(url)) return "unavailable";
  const deadline = Date.now() + Math.max(1, timeoutMs);
  let disconnects = 0;
  while (Date.now() < deadline) {
    if (!isCurrent()) return "cancelled";
    const remaining = deadline - Date.now();
    let result = "retry";
    try {
      result = await probe(url, Math.min(probeTimeoutMs, Math.max(1, remaining)), undefined, setProbeAbort);
    } catch (_) {
      // Temporary local service or connection errors must not discard the swarm.
    }
    if (!isCurrent()) return "cancelled";
    if (result === "ready" || result === "session-lost" || result === "unavailable" || result === "cancelled") return result;
    disconnects = result === "disconnected" ? disconnects + 1 : 0;
    if (disconnects >= 3) return "session-lost";
    const wait = Math.min(retryMs, deadline - Date.now());
    if (wait > 0) await new Promise((resolve) => setTimeout(resolve, wait));
  }
  return "unavailable";
}
