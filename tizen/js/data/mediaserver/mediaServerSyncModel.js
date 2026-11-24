import { normalizeMediaServerAddress } from "./mediaBrowserClient.js";

const providers = new Set(["jellyfin", "emby"]);
const libraryKinds = new Set(["movie", "series", "mixed", "collection"]);
const idPattern = /^[a-zA-Z0-9._:-]{1,256}$/;
const addressPattern = /^https?:\/\/([a-z0-9.-]+|\[[0-9a-f:.]+\])(:[0-9]{1,5})?(\/[a-z0-9._~%/-]*)?$/i;

export const serverSyncKey = (server) =>
  `${server.providerId || server.provider_id}|${server.remoteServerId || server.remote_server_id}|${server.remoteUserId || server.remote_user_id}`;

export function toSyncedServer(server) {
  if (!server || !Array.isArray(server.libraries) || server.libraries.length > 128) return null;
  const provider = server.providerId || server.provider_id;
  if (!providers.has(provider) || typeof server.address !== "string" || !addressPattern.test(server.address)) return null;
  const address = normalizeMediaServerAddress(server.address, provider);
  const remoteServerId = server.remoteServerId || server.remote_server_id;
  const remoteUserId = server.remoteUserId || server.remote_user_id;
  if (!address || address.length > 2048 || ![server.id, remoteServerId, remoteUserId].every(
    (value) => typeof value === "string" && idPattern.test(value)
  )) return null;
  const name = typeof server.name === "string" ? server.name.trim() : "";
  if (!name || name.length > 256) return null;
  const libraries = [];
  const IDs = new Set();
  for (const library of server.libraries) {
    if (!library || typeof library.id !== "string" || !idPattern.test(library.id) || IDs.has(library.id) ||
        typeof library.name !== "string" || !library.name || library.name.length > 512 ||
        !libraryKinds.has(library.kind) || typeof library.selected !== "boolean") return null;
    IDs.add(library.id);
    libraries.push({ id: library.id, name: library.name, kind: library.kind, selected: library.selected });
  }
  // Do not transmit passwords, tokens, usernames nor arbitrary passthrough fields.
  return {
    id: server.id,
    provider_id: provider,
    remote_server_id: remoteServerId,
    remote_user_id: remoteUserId,
    name,
    address,
    libraries,
    enabled: server.enabled !== false,
    use_catalog_metadata: (server.useCatalogMetadata ?? server.use_catalog_metadata) === true,
    import_watch_state: (server.importWatchState ?? server.import_watch_state) === true
  };
}

export function toSyncPayload(connections, preserved = []) {
  const byKey = new Map();
  for (const entry of [...preserved, ...connections]) {
    const clean = toSyncedServer(entry);
    if (clean) byKey.set(serverSyncKey(clean), clean);
  }
  return [...byKey.values()];
}

export function parseSyncedServers(rows) {
  if (!Array.isArray(rows) || rows.length > 1) throw new Error("Invalid media server sync response");
  if (!rows.length) return { servers: [], updatedAt: null };
  const row = rows[0];
  if (!Array.isArray(row?.servers_json) || row.servers_json.length > 50 ||
      typeof row.updated_at !== "string" || !Number.isFinite(Date.parse(row.updated_at))) {
    throw new Error("Invalid media server sync response");
  }
  const servers = row.servers_json.map((raw) => {
    const clean = toSyncedServer(raw);
    if (!clean || Object.keys(raw).some((key) => !Object.prototype.hasOwnProperty.call(clean, key))) {
      throw new Error("Invalid media server sync metadata");
    }
    return clean;
  });
  if (new Set(servers.map(serverSyncKey)).size !== servers.length)
    throw new Error("Duplicate media server sync identity");
  // Preserve the original text, including PostgreSQL's sub-millisecond precision.
  return { servers, updatedAt: row.updated_at };
}

const indexed = (entries) => new Map(entries.map((entry) => [serverSyncKey(entry), entry]));

export function rebaseMediaServerChanges(local, remote) {
  const latest = indexed(remote);
  const desired = indexed(toSyncPayload(local.connections, local.preserved));
  const baseline = local.syncedPayload == null ? null : indexed(local.syncedPayload);
  if (baseline && !local.pendingPush) return remote;

  // Only replay local changes relative to the most recent acknowledged snapshot.
  // First setup contributes local-only rows, never bulk-replaces the remote.
  if (baseline) {
    for (const [key] of baseline) if (!desired.has(key)) latest.delete(key);
  }
  for (const [key, entry] of desired) {
    const previous = baseline?.get(key);
    if (!previous) {
      if (!latest.has(key)) latest.set(key, entry);
      continue;
    }
    // An old preference edit does not recreate a row removed on another device.
    if (latest.has(key) && JSON.stringify(previous) !== JSON.stringify(entry)) {
      const next = { ...latest.get(key) };
      for (const field of ["enabled", "use_catalog_metadata", "import_watch_state", "libraries"]) {
        if (JSON.stringify(previous[field]) !== JSON.stringify(entry[field])) next[field] = entry[field];
      }
      latest.set(key, next);
    }
  }
  return [...latest.values()];
}

export function withLinkedMediaServerPreferences(local, remote) {
  if (!remote || serverSyncKey(local) !== serverSyncKey(remote)) return local;
  const selected = new Map(remote.libraries.map((library) => [library.id, library.selected]));
  return {
    ...local,
    // Keep the locally authenticated endpoint, username, and token together.
    // Remote metadata must never redirect local credentials to another host.
    enabled: remote.enabled,
    useCatalogMetadata: remote.use_catalog_metadata,
    importWatchState: remote.import_watch_state,
    libraries: local.libraries.map((library) => ({
      ...library,
      selected: selected.has(library.id) ? selected.get(library.id) : library.selected
    }))
  };
}

export function reconcileLinkedMediaServers(local, servers) {
  const remote = indexed(servers);
  const known = indexed(local.syncedPayload ?? []);
  const connections = [];
  for (const connection of local.connections) {
    const clean = toSyncedServer(connection);
    if (!clean) {
      connections.push(connection);
      continue;
    }
    const key = serverSyncKey(clean);
    if (remote.has(key)) connections.push(withLinkedMediaServerPreferences(connection, remote.get(key)));
    else if (!known.has(key)) connections.push(connection);
  }
  const linked = new Set(connections.map(serverSyncKey));
  return { connections, unlinked: servers.filter((entry) => !linked.has(serverSyncKey(entry))) };
}
