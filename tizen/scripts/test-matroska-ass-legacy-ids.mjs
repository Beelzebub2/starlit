import assert from "node:assert/strict";
import { createRequire } from "node:module";
import test from "node:test";
import vm from "node:vm";
import { adaptWebOsEmbeddedSubtitleParser } from "./webosEmbeddedSubtitleCompatibility.mjs";

globalThis.localStorage = { getItem: () => null, setItem() {}, removeItem() {} };
const { formatSubtitleCodecLabel, isAssSubtitleCodec, isEmbeddedTextSubtitleSourceTrack } =
  await import("../js/ui/screens/player/playerScreenHelpers-04-format-subtitle-codec-label.js");
const { _test: parser } = createRequire(import.meta.url)("../services/tizen/runtime/embedded-text-subtitle-parser.cjs");

test("legacy Matroska ASS/SSA tracks participate in the TV text and style paths", () => {
  for (const codecId of ["S_ASS", "s_ssa", "S_TEXT/ASS", "s_text/ssa"]) {
    const track = { type: 0x11, number: 4, codecId };
    assert.equal(isAssSubtitleCodec(codecId), true, codecId);
    assert.equal(isEmbeddedTextSubtitleSourceTrack({ codec: codecId }), true, codecId);
    assert.equal(formatSubtitleCodecLabel(codecId), "SSA", codecId);
    assert.equal(parser.isTextSubtitleTrack(track), true, codecId);
    assert.equal(parser.isAssTextSubtitleTrack(track), true, codecId);
    assert.equal(parser.selectTimelineTextTrack({ tracks: [track], cues: [] }, 4), track);
  }
  for (const codecId of ["S_ASS_EXTRA", "S_SSA2", "S_TEXTASS", "S_HDMV/PGS"]) {
    const track = { type: 0x11, number: 4, codecId };
    assert.equal(isAssSubtitleCodec(codecId), false, codecId);
    assert.equal(parser.isTextSubtitleTrack(track), false, codecId);
  }
});

test("the pinned webOS bundle compatibility transform accepts legacy and current IDs only", () => {
  const original = String.raw`(text) => /^S_TEXT\/(?:ASS|SSA)$/i.test(text) || false`;
  const matches = vm.runInNewContext(adaptWebOsEmbeddedSubtitleParser(original));
  for (const id of ["S_ASS", "s_ssa", "S_TEXT/ASS", "S_TEXT/SSA"]) assert.equal(matches(id), true, id);
  for (const id of ["S_ASS_EXTRA", "S_SSA2", "S_HDMV/PGS"]) assert.equal(matches(id), false, id);
  assert.throws(() => adaptWebOsEmbeddedSubtitleParser("unexpected source"), /Expected one pinned/);
  assert.throws(() => adaptWebOsEmbeddedSubtitleParser(original.repeat(2)), /Expected one pinned/);
});
