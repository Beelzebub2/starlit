const normalizeHashes = (hashes = []) =>
  Array.from(new Set(
    (hashes || [])
      .map((hash) => String(hash || "").trim().toLowerCase())
      .filter(Boolean)
  ));

export function torboxCachedEntries(response = {}) {
  const data = response?.data?.data;
  if (!data || typeof data !== "object" || Array.isArray(data)) return {};
  return Object.entries(data).reduce((entries, [hash, item]) => {
    const normalized = String(hash || "").trim().toLowerCase();
    if (normalized) {
      entries[normalized] = { name: item?.name || null, size: item?.size || null };
    }
    return entries;
  }, {});
}

// TorBox may index an alias but respond with its canonical hash. Batch results
// cannot safely identify which missing alias produced an unexpected hash.
export async function resolveAlternativeHashMatches(cached = {}, requestedHashes = [], fetchCached) {
  const requested = normalizeHashes(requestedHashes);
  const normalizedCached = Object.entries(cached || {}).reduce((entries, [hash, item]) => {
    entries[String(hash).trim().toLowerCase()] = item;
    return entries;
  }, {});
  if (!requested.length || Object.keys(normalizedCached).every((hash) => requested.includes(hash))) {
    return normalizedCached;
  }
  if (requested.length === 1) {
    const entries = Object.values(normalizedCached);
    const item = normalizedCached[requested[0]] || (entries.length === 1 ? entries[0] : null);
    return item ? { [requested[0]]: item } : null;
  }
  if (typeof fetchCached !== "function") return null;

  const resolved = { ...normalizedCached };
  const missing = requested.filter((hash) => !resolved[hash]);
  // Bound concurrent account calls; a single aliased batch can be quite large.
  for (let index = 0; index < missing.length; index += 4) {
    const batch = await Promise.all(missing.slice(index, index + 4).map(async (hash) => {
      try {
        const response = await fetchCached(hash);
        if (!response) return { hash, known: false };
        const entries = Object.values(response);
        const item = response[hash] || (entries.length === 1 ? entries[0] : null);
        return { hash, item, known: Boolean(item) || entries.length === 0 };
      } catch (_) {
        return { hash, known: false };
      }
    }));
    if (batch.some((entry) => !entry.known)) return null;
    batch.forEach(({ hash, item }) => {
      if (item) resolved[hash] = item;
    });
  }
  return resolved;
}
