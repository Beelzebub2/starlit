// On older TVs the browser MSE buffer competes with the native AVPlay plane,
// poster bitmaps and the EngineFS process. Modern TV behavior stays unchanged.
export const TIZEN_CONSTRAINED_HLS_FORWARD_SECONDS = 24;
export const TIZEN_CONSTRAINED_HLS_BUFFER_BYTES = 32 * 1024 * 1024;
export function getTizenHlsBufferPolicy({ isTizen = false, constrained = false, defaultSeconds = 50 } = {}) {
  return isTizen && constrained
    ? { forwardSeconds: TIZEN_CONSTRAINED_HLS_FORWARD_SECONDS, maxBufferBytes: TIZEN_CONSTRAINED_HLS_BUFFER_BYTES }
    : { forwardSeconds: defaultSeconds, maxBufferBytes: null };
}
