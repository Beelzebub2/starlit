// Geometry is sampled once by ScreenUtils before focus classes change.
// Choose the same nearest-row/column candidate without sorting and allocating
// several full candidate arrays on every held remote-key repeat.
export function pickDirectionalTarget(nodes, current, direction, rects) {
  if (!nodes?.length || !current || !["up", "down", "left", "right"].includes(direction)) return null;
  const currentRect = rects.get(current) || current.getBoundingClientRect?.();
  if (!currentRect) return null;
  const cx = currentRect.left + currentRect.width / 2;
  const cy = currentRect.top + currentRect.height / 2;
  const vertical = direction === "up" || direction === "down";
  const candidates = [];
  let nearestPrimary = Infinity;
  for (const node of nodes) {
    if (node === current) continue;
    const rect = rects.get(node);
    if (!rect) continue;
    const dx = rect.left + rect.width / 2 - cx;
    const dy = rect.top + rect.height / 2 - cy;
    if ((direction === "up" && dy >= -2) ||
        (direction === "down" && dy <= 2) ||
        (direction === "left" && dx >= -2) ||
        (direction === "right" && dx <= 2)) continue;
    const primary = Math.abs(vertical ? dy : dx);
    const secondary = Math.abs(vertical ? dx : dy);
    const axisTolerance = vertical
      ? Math.max(currentRect.width * 0.7, rect.width * 0.7, 48)
      : Math.max(currentRect.height * 0.7, rect.height * 0.7, 48);
    candidates.push({ node, primary, secondary, aligned: secondary <= axisTolerance });
    if (primary < nearestPrimary) nearestPrimary = primary;
  }
  if (!candidates.length) return null;
  const tolerance = vertical ? Math.max(currentRect.height * 0.9, 42) : Math.max(currentRect.width * 0.9, 42);
  let aligned = null;
  let fallback = null;
  for (const candidate of candidates) {
    if (candidate.primary > nearestPrimary + tolerance) continue;
    // Preserve stable DOM tie-breaking from the old aligned/secondary sorts.
    if (candidate.aligned && (!aligned || candidate.secondary < aligned.secondary)) aligned = candidate;
    if (!fallback || candidate.secondary < fallback.secondary ||
        (candidate.secondary === fallback.secondary && candidate.primary < fallback.primary)) {
      fallback = candidate;
    }
  }
  return (aligned || fallback)?.node || null;
}
