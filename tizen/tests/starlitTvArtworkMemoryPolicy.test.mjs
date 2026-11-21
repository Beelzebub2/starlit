import test from "node:test";
import assert from "node:assert/strict";
import { canUseModernTvArtworkBounding } from "../js/platform/tvPerformancePolicy.js";

test("bounds artwork on >=2GB modern TVs even when measured slow", () => {
  assert.equal(canUseModernTvArtworkBounding({
    isTvRuntime: true, isLegacyTvRuntime: false,
    isPerformanceConstrained: true, isMeasuredSlow: true
  }, 2), true);
  assert.equal(canUseModernTvArtworkBounding({
    isTvRuntime: true, isLegacyTvRuntime: false,
    isPerformanceConstrained: false, isMeasuredSlow: false
  }, 2), true);
});

test("avoids costly artwork re-encoding on legacy and low-RAM TVs", () => {
  const modern = { isTvRuntime: true, isLegacyTvRuntime: false, isPerformanceConstrained: false };
  assert.equal(canUseModernTvArtworkBounding(modern, 1), false);
  assert.equal(canUseModernTvArtworkBounding({...modern, isLegacyTvRuntime: true}, 4), false);
  assert.equal(canUseModernTvArtworkBounding({isTvRuntime: false}, 4), false);
  assert.equal(canUseModernTvArtworkBounding({
    ...modern, isPerformanceConstrained: true, isMeasuredSlow: false
  }, 4), false);
});
