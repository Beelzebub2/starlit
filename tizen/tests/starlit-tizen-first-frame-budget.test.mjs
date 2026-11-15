import test from "node:test";
import assert from "node:assert/strict";
import { TIZEN_FIRST_FRAME_DEADLINE_MS, tizenFirstFrameRemainingMs, canContinueTizenFirstFrameWait }
  from "../js/core/player/tizenFirstFrameBudget.js";

test("Tizen first-frame clock does not reset when transport advances", () => {
  const first = 1_000;
  assert.equal(tizenFirstFrameRemainingMs(first, first), TIZEN_FIRST_FRAME_DEADLINE_MS);
  assert.equal(tizenFirstFrameRemainingMs(first, first + 30_000), TIZEN_FIRST_FRAME_DEADLINE_MS - 30_000);
  assert.equal(canContinueTizenFirstFrameWait(first, first + TIZEN_FIRST_FRAME_DEADLINE_MS - 1), true);
  assert.equal(canContinueTizenFirstFrameWait(first, first + TIZEN_FIRST_FRAME_DEADLINE_MS), false);
  assert.equal(tizenFirstFrameRemainingMs(first, first + TIZEN_FIRST_FRAME_DEADLINE_MS + 10_000), 0);
});
test("Unstarted clock is neutral and clock skew cannot increase the deadline", () => {
  assert.equal(tizenFirstFrameRemainingMs(0), null);
  assert.equal(canContinueTizenFirstFrameWait(0), true);
  assert.equal(tizenFirstFrameRemainingMs(1000, 950), TIZEN_FIRST_FRAME_DEADLINE_MS);
  assert.equal(tizenFirstFrameRemainingMs(1000, NaN), 0);
});
