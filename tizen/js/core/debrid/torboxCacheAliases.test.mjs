import test from "node:test";
import assert from "node:assert/strict";
import {
  resolveAlternativeHashMatches,
  torboxCachedEntries
} from "./torboxCacheAliases.js";

const requested = "a".repeat(40);
const canonical = "b".repeat(40);
const exact = "c".repeat(40);
const item = { name: "sample.mkv", size: 1024 };

test("TorBox response maps canonical hashes to normalized cached entries", () => {
  assert.deepEqual(
    torboxCachedEntries({ data: { data: { [canonical.toUpperCase()]: item } } }),
    { [canonical]: item }
  );
  assert.deepEqual(torboxCachedEntries({ data: { data: null } }), {});
});

test("exact cache hits require no extra requests", async () => {
  const cached = { [requested]: item };
  assert.deepEqual(await resolveAlternativeHashMatches(cached, [requested], () => {
    throw Error("should not call TorBox again");
  }), cached);
});

test("single canonical reply is remapped to the requested alias", async () => {
  const result = await resolveAlternativeHashMatches({ [canonical]: item }, [requested]);
  assert.deepEqual(result, { [requested]: item });
});

test("mixed exact/canonical batch only rechecks missing hashes", async () => {
  const asked = [];
  const result = await resolveAlternativeHashMatches(
    { [exact]: item, [canonical]: item },
    [exact, requested],
    async (hash) => {
      asked.push(hash);
      return { [canonical]: item };
    }
  );
  assert.deepEqual(asked, [requested]);
  assert.equal(result[exact], item);
  assert.equal(result[requested], item);
});

test("ambiguous canonical answers are not attributed to unknown hashes", async () => {
  const result = await resolveAlternativeHashMatches(
    { [canonical]: item, [exact]: item },
    [requested]
  );
  assert.equal(result, null);
});

test("duplicate uppercase hashes normalize to one requested lookup", async () => {
  assert.deepEqual(
    await resolveAlternativeHashMatches({ [canonical]: item }, [requested.toUpperCase(), requested]),
    { [requested]: item }
  );
});

test("failed individual checks leave the batch unknown rather than incorrectly uncached", async () => {
  const result = await resolveAlternativeHashMatches(
    { [canonical]: item },
    [requested, exact],
    async () => null
  );
  assert.equal(result, null);
});

test("unexpected canonical batch limits simultaneous follow-up requests", async () => {
  const hashes = Array.from({ length: 10 }, (_, index) => (index + 20).toString(16).padStart(40, "0"));
  let active = 0;
  let peak = 0;
  const resolved = await resolveAlternativeHashMatches(
    { [canonical]: item },
    hashes,
    async (hash) => {
      active += 1;
      peak = Math.max(peak, active);
      await new Promise((resolve) => setTimeout(resolve, 1));
      active -= 1;
      return { [hash]: item };
    }
  );
  assert.equal(peak, 4);
  assert.equal(Object.keys(resolved).length, hashes.length + 1);
});
