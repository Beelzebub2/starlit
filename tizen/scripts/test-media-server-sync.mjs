import { test } from "node:test";
import assert from "node:assert/strict";
import {
  parseSyncedServers, rebaseMediaServerChanges, reconcileLinkedMediaServers,
  toSyncedServer, toSyncPayload, withLinkedMediaServerPreferences, serverSyncKey
} from "../js/data/mediaserver/mediaServerSyncModel.js";

const stamp = "2026-01-02T03:04:05.123456+00:00";
const connection = ({
  id: "server-one",
  providerId: "jellyfin",
  remoteServerId: "instance-a",
  remoteUserId: "user-a",
  name: "Demo server",
  address: "https://media.example.test/base",
  token: "local-authorization-only",
  userName: "local-sign-in-name",
  enabled: true,
  useCatalogMetadata: false,
  importWatchState: false,
  libraries: [{ id: "media-library-a", name: "Films", kind: "movie", selected: true }]
});
const fixture = {...toSyncedServer(connection)};

test("wire metadata never includes reusable credentials or unsafe URLs", () => {
  const input = { ...connection, password: "local-password", libraries: [{ ...connection.libraries[0], token: "nested-token" }] };
  const encoded = JSON.stringify(toSyncPayload([input]));
  for (const secret of ["local-authorization-only", "local-sign-in-name", "local-password", "nested-token"]) {
    assert.ok(!encoded.includes(secret), secret);
  }
  for (const address of [
    "https://name:secret@media.example.test", "https://media.example.test?auth=secret",
    "https://media.example.test#secret", "file:///tmp/local", "https://media.example.test\\bad"
  ]) assert.equal(toSyncedServer({ ...connection, address }), null);
  assert.equal(toSyncedServer({ ...connection, remoteUserId: "bad|delimiter" }), null);
  assert.equal(toSyncedServer({ ...connection, libraries: [connection.libraries[0], connection.libraries[0]] }), null);
});

test("pull distinguishes never synced, empty, and valid metadata without accepting old token formats", () => {
  assert.deepEqual(parseSyncedServers([]), { servers: [], updatedAt: null });
  assert.deepEqual(parseSyncedServers([{ servers_json: [], updated_at: stamp }]), { servers: [], updatedAt: stamp });
  assert.deepEqual(parseSyncedServers([{ servers_json: [fixture], updated_at: stamp }]), { servers: [fixture], updatedAt: stamp });
  for (const rows of [
    {},
    [{}],
    [{ servers_json: [], updated_at: "unknown" }],
    [{ servers_json: [null], updated_at: stamp }],
    [{ servers_json: [{...fixture, token: "not-on-wire"}], updated_at: stamp }],
    [{ servers_json: [{...fixture, provider_id: "not-supported"}], updated_at: stamp }],
    [{ servers_json: [fixture, { ...fixture, id: "duplicate" }], updated_at: stamp }]
  ]) assert.throws(() => parseSyncedServers(rows));
});

test("first pull creates a metadata-only relink hint, never a signed-in connection", () => {
  const result = reconcileLinkedMediaServers({ connections: [], syncedPayload: null }, [fixture]);
  assert.deepEqual(result.connections, []);
  assert.deepEqual(result.unlinked, [fixture]);
  assert.deepEqual(toSyncPayload([], result.unlinked), [fixture]);
  assert.equal(withLinkedMediaServerPreferences({ ...connection, remoteUserId: "different-account" }, fixture).token, connection.token);
});

test("metadata can restore preferences but cannot redirect an authenticated local endpoint", () => {
  const untrustedAddress = {
    ...fixture, address: "https://other.example.test", enabled: false,
    use_catalog_metadata: true, import_watch_state: true,
    libraries: [{ ...fixture.libraries[0], selected: false }]
  };
  const linked = reconcileLinkedMediaServers({ connections: [connection], syncedPayload: [fixture] }, [untrustedAddress]);
  assert.deepEqual(linked.unlinked, []);
  assert.equal(linked.connections[0].address, connection.address);
  assert.equal(linked.connections[0].token, connection.token);
  assert.equal(linked.connections[0].userName, connection.userName);
  assert.equal(linked.connections[0].enabled, false);
  assert.equal(linked.connections[0].useCatalogMetadata, true);
  assert.equal(linked.connections[0].importWatchState, true);
  assert.equal(linked.connections[0].libraries[0].selected, false);
});

test("rebasing local metadata preserves another device's edits or additions and does not revive a deletion", () => {
  const second = {...fixture, id: "second", remote_server_id: "instance-b"};
  const local = { connections: [{ ...connection, useCatalogMetadata: true }],
    preserved: [], syncedPayload: [fixture], pendingPush: true };
  assert.deepEqual(rebaseMediaServerChanges(local, [second]), [second], "stale preference edit must not undo a deletion");
  const fresh = { ...fixture, import_watch_state: true };
  assert.deepEqual(rebaseMediaServerChanges(local, [fresh, second]), [
    { ...fresh, use_catalog_metadata: true }, second
  ]);
  assert.deepEqual(rebaseMediaServerChanges({ ...local, connections: [] }, [fixture, second]), [second]);
  assert.deepEqual(rebaseMediaServerChanges({ ...local, syncedPayload: null }, [fresh, second]), [fresh, second]);
});

test("removing acknowledged metadata clears a linked entry but keeps unrelated local-only servers", () => {
  const localOnly = {...connection,id:"local",remoteServerId:"local-only"};
  const result = reconcileLinkedMediaServers({ connections: [connection, localOnly], syncedPayload: [fixture] }, []);
  assert.deepEqual(result.connections.map(serverSyncKey), [serverSyncKey(localOnly)]);
  assert.deepEqual(result.unlinked, []);
});
