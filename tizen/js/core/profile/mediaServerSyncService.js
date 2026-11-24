import { AuthManager } from "../auth/authManager.js";
import { ProfileManager } from "./profileManager.js";
import { SupabaseApi } from "../../data/remote/supabase/supabaseApi.js";
import { MediaServerStore } from "../../data/mediaserver/mediaServerStore.js";
import {
  parseSyncedServers,
  rebaseMediaServerChanges,
  reconcileLinkedMediaServers
} from "../../data/mediaserver/mediaServerSyncModel.js";
import { getSyncClientId } from "../sync/syncClientIdentity.js";
import { isSyncBackoffActive, getSyncBackoffRemainingMs } from "../sync/syncBackoffPolicy.js";
import { registerSessionTeardownHandler } from "../auth/sessionLifecycle.js";
let generation = 0;
let queue = Promise.resolve();
let foregroundTimer = null;
let lastPull = 0;
let started = false;
const pushTimers = new Map();
async function scopeFor(profileId) {
  const profileVersion = ProfileManager.getProfileDataVersion(profileId);
  if (
    !AuthManager.isAuthenticated ||
    String(ProfileManager.getActiveProfileId()) !== String(profileId)
  )
    return null;
  const ownerId = String((await AuthManager.getEffectiveUserId()) || "");
  if (
    !AuthManager.isAuthenticated ||
    String(ProfileManager.getActiveProfileId()) !== String(profileId) ||
    profileVersion !== ProfileManager.getProfileDataVersion(profileId)
  )
    return null;
  return ownerId ? { ownerId, profileId: String(profileId), profileVersion } : null;
}
async function stillCurrent(scope, version) {
  if (version !== generation) return false;
  const current = await scopeFor(scope.profileId);
  return (
    version === generation &&
    current?.ownerId === scope.ownerId &&
    current?.profileVersion === scope.profileVersion
  );
}
function notifyRemoteChange() {
  globalThis.document?.dispatchEvent?.(
    new CustomEvent("nuvio:media-servers-changed", { detail: { remote: true } })
  );
}
async function sync(profileId, version) {
  if (version !== generation || isSyncBackoffActive()) return false;
  const scope = await scopeFor(profileId);
  if (!scope || !(await stillCurrent(scope, version))) return false;

  for (let attempt = 0; attempt < 2; attempt++) {
    const local = MediaServerStore.get(scope.profileId);
    let remote = parseSyncedServers(await SupabaseApi.rpc(
      "sync_pull_media_servers", { p_profile_id: Number(scope.profileId) }, true
    ));
    if (!(await stillCurrent(scope, version))) return false;
    if (MediaServerStore.get(scope.profileId).revision !== local.revision) return false;
    const payload = rebaseMediaServerChanges(local, remote.servers);
    if (JSON.stringify(payload) !== JSON.stringify(remote.servers)) {
      try {
        remote = parseSyncedServers(await SupabaseApi.rpc("sync_push_media_servers", {
          p_profile_id: Number(scope.profileId),
          p_servers: payload,
          p_origin_client_id: getSyncClientId(),
          p_expected_updated_at: remote.updatedAt
        }, true));
      } catch (error) {
        if (attempt === 0 && Number(error?.status) === 409 &&
            await stillCurrent(scope, version)) continue;
        throw error;
      }
    }
    if (!(await stillCurrent(scope, version))) return false;
    const { connections, unlinked } = reconcileLinkedMediaServers(local, remote.servers);
    const changed = JSON.stringify(local.connections) !== JSON.stringify(connections) ||
      JSON.stringify(local.preserved) !== JSON.stringify(unlinked);
    if (!MediaServerStore.applySync(local, connections, remote.servers, unlinked, scope.profileId))
      return false;
    if (changed) notifyRemoteChange();
    lastPull = Date.now();
    return changed;
  }
  return false;
}
function enqueue(profileId) {
  const version = generation;
  const operation = queue
    .catch(() => false)
    .then(() => sync(profileId, version))
    .catch(() => false);
  queue = operation;
  return operation;
}
export const MediaServerSyncService = {
  start() {
    if (started) return;
    started = true;
    globalThis.document?.addEventListener?.("nuvio:media-servers-changed", (event) => {
      if (!event.detail?.remote) this.requestPush();
    });
  },
  syncFromRemote(profileId = ProfileManager.getActiveProfileId()) {
    return enqueue(String(profileId));
  },
  requestPush(profileId = ProfileManager.getActiveProfileId()) {
    if (!AuthManager.isAuthenticated) return;
    const id = String(profileId);
    clearTimeout(pushTimers.get(id));
    pushTimers.set(
      id,
      setTimeout(
        () => {
          pushTimers.delete(id);
          if (isSyncBackoffActive()) {
            this.requestPush(id);
            return;
          }
          void enqueue(id);
        },
        Math.max(500, getSyncBackoffRemainingMs())
      )
    );
  },
  requestForegroundPull({ force = false } = {}) {
    if (
      !AuthManager.isAuthenticated ||
      foregroundTimer ||
      (!force && Date.now() - lastPull < 60000)
    )
      return;
    const version = generation;
    foregroundTimer = setTimeout(
      () => {
        foregroundTimer = null;
        if (version === generation) void this.syncFromRemote();
      },
      force ? 0 : 2500
    );
  },
  stop() {
    generation++;
    clearTimeout(foregroundTimer);
    foregroundTimer = null;
    lastPull = 0;
    for (const timer of pushTimers.values()) clearTimeout(timer);
    pushTimers.clear();
    return queue.then(
      () => true,
      () => true
    );
  }
};
registerSessionTeardownHandler(() => MediaServerSyncService.stop());
