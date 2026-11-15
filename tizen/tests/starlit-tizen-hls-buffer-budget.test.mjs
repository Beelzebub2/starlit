import test from "node:test";
import assert from "node:assert/strict";
import { getTizenHlsBufferPolicy } from "../js/core/player/tizenHlsBufferPolicy.js";
test("legacy Tizen MSE uses a shorter bounded HLS forward window", () => {
  assert.deepEqual(getTizenHlsBufferPolicy({isTizen:true,constrained:true}), {forwardSeconds:24,maxBufferBytes:32*1024*1024});
});
test("modern Samsung and webOS buffer policies remain unchanged", () => {
  assert.deepEqual(getTizenHlsBufferPolicy({isTizen:true,constrained:false}), {forwardSeconds:50,maxBufferBytes:null});
  assert.deepEqual(getTizenHlsBufferPolicy({isTizen:false,constrained:true}), {forwardSeconds:50,maxBufferBytes:null});
});
