/** In-tab snapshot for the viral “handwritten → digital list” share card. */

let snapshot = null;

export function setShareSnapshot(next) {
  snapshot = next
    ? {
        items: (next.items || []).map((x) => String(x || '').trim()).filter(Boolean).slice(0, 12),
        photoBlob: next.photoBlob || null,
        quoteCount: next.quoteCount ?? null,
        lowestPrice: next.lowestPrice ?? null,
        createdAt: Date.now(),
      }
    : null;
}

export function getShareSnapshot() {
  return snapshot;
}

export function patchShareSnapshot(partial) {
  if (!snapshot) {
    setShareSnapshot(partial);
    return snapshot;
  }
  snapshot = { ...snapshot, ...partial, createdAt: snapshot.createdAt };
  return snapshot;
}

export function clearShareSnapshot() {
  snapshot = null;
}
