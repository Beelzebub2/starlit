import { createProfileScopedStore } from "../local/profileScopedStore.js";
const store = createProfileScopedStore({
  key: "mediaServerConnections",
  seedFromPrimary: false,
  normalize: (value = {}) => ({
    connections: Array.isArray(value.connections) ? value.connections : [],
    preserved: Array.isArray(value.preserved) ? value.preserved : [],
    revision: Number(value.revision || 0),
    pendingPush: value.pendingPush === true,
    syncedPayload: Array.isArray(value.syncedPayload) ? value.syncedPayload : null
  })
});
export const MediaServerStore = {
  get: (profileId) => (profileId == null ? store.get() : store.getForProfile(profileId)),
  set(connections, profileId) {
    const current = this.get(profileId);
    return store.setForProfile(
      profileId,
      { ...current, connections, revision: current.revision + 1, pendingPush: true },
      { silentSync: true }
    );
  },
  applySync(snapshot, connections, syncedPayload, preserved, profileId) {
    const current = this.get(profileId);
    if (current.revision !== snapshot.revision) return false;
    const changed = JSON.stringify(current.connections) !== JSON.stringify(connections) ||
      JSON.stringify(current.preserved) !== JSON.stringify(preserved);
    store.setForProfile(
      profileId,
      {
        connections,
        preserved,
        syncedPayload,
        pendingPush: false,
        revision: current.revision + (changed ? 1 : 0)
      },
      { silentSync: true }
    );
    return true;
  },
  update(id, partial, profileId) {
    return this.set(
      this.get(profileId).connections.map((entry) =>
        entry.id === id ? { ...entry, ...partial } : entry
      ),
      profileId
    );
  }
};
