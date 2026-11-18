// Match OCR shop rows (base name + qty + price) onto quote editor rows.

function fold(text) {
  return String(text || '')
    .toLowerCase()
    .normalize('NFKC')
    .replace(/[^a-z0-9\u0900-\u097f\s]+/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}

function scoreName(asked, scanned) {
  const a = fold(asked);
  const b = fold(scanned);
  if (!a || !b) return 0;
  if (a === b) return 100;
  if (a.includes(b) || b.includes(a)) return 85;
  const at = new Set(a.split(' ').filter(Boolean));
  const bt = b.split(' ').filter(Boolean);
  if (!bt.length) return 0;
  const hit = bt.filter((t) => at.has(t) || [...at].some((x) => x.includes(t) || t.includes(x))).length;
  return Math.round((hit / Math.max(at.size, bt.length)) * 70);
}

/**
 * Apply scanned shop rows onto quote rows: fill price (and qty when useful).
 * Returns { rows, matched, skipped }.
 */
export function applyShopScan(quoteRows, scannedItems) {
  const scanned = (scannedItems || [])
    .map((s) => ({
      name: s.baseName || s.base_name || s.name || '',
      quantity: s.quantity,
      unit: s.unit,
      price: s.price != null ? Number(s.price) : s.mrp?.amount != null ? Number(s.mrp.amount) : null,
    }))
    .filter((s) => s.name);

  const used = new Set();
  let matched = 0;
  const next = quoteRows.map((row) => {
    let best = -1;
    let bestScore = 0;
    for (let i = 0; i < scanned.length; i += 1) {
      if (used.has(i)) continue;
      const sc = scoreName(row.name, scanned[i].name);
      if (sc > bestScore) {
        bestScore = sc;
        best = i;
      }
    }
    if (best < 0 || bestScore < 50) return row;
    used.add(best);
    matched += 1;
    const hit = scanned[best];
    const patch = { ...row };
    if (hit.price != null && Number.isFinite(hit.price) && hit.price > 0) {
      patch.price = Number.isInteger(hit.price) ? String(hit.price) : hit.price.toFixed(2);
      if (row.status === 'UNAVAILABLE') patch.status = 'AVAILABLE';
    }
    if (hit.quantity != null && Number(hit.quantity) > 0) {
      const q = String(hit.quantity);
      if (q !== row.askedQty) {
        patch.qty = q;
        patch.qtyOpen = true;
        if (patch.status === 'AVAILABLE') patch.status = 'PARTIAL';
      }
    }
    if (hit.unit && hit.unit !== row.unit) {
      patch.unit = hit.unit;
    }
    return patch;
  });

  return { rows: next, matched, skipped: scanned.length - matched };
}
