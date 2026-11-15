// Native AVPlay can report READY while decoding never presents a frame.
// Network or HTTP progress must not keep a TV's loading overlay alive forever.
// This clock follows one selected source through its automatic recovery ladder.
export const TIZEN_FIRST_FRAME_DEADLINE_MS = 90_000;
export function tizenFirstFrameRemainingMs(startedAt, now = Date.now()) {
  const start = Number(startedAt);
  if (!Number.isFinite(start) || start <= 0) return null;
  const elapsed = Math.max(0, Number(now) - start);
  if (!Number.isFinite(elapsed)) return 0;
  return Math.max(0, TIZEN_FIRST_FRAME_DEADLINE_MS - elapsed);
}
export function canContinueTizenFirstFrameWait(startedAt, now = Date.now()) {
  const remaining = tizenFirstFrameRemainingMs(startedAt, now);
  return remaining === null || remaining > 0;
}
