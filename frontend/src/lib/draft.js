import { DEFAULT_UNIT, UNITS, formatQty } from './units.js';

// The customer's unsent list, kept per user in localStorage:
// { items: [{ id, name, quantity, unit, unsure?, brand?, options?, selection?, … }], mode, storeId?, storeName?, fromPhoto? }
export const MAX_ITEMS = 50;
const key = (userId) => `kirana.draft.${userId}`;

let nextId = 0;
export function newRow() {
  nextId += 1;
  return { id: `r${Date.now().toString(36)}${nextId}`, name: '', quantity: '', unit: DEFAULT_UNIT };
}

export function emptyDraft() {
  return { items: [newRow(), newRow(), newRow()], mode: 'NEAREST', storeId: null, storeName: null };
}

export function loadDraft(userId) {
  try {
    const draft = JSON.parse(localStorage.getItem(key(userId)));
    if (draft && Array.isArray(draft.items)) return draft;
  } catch {
    // missing, corrupt or storage unavailable
  }
  return null;
}

export function saveDraft(userId, draft) {
  try {
    localStorage.setItem(key(userId), JSON.stringify(draft));
  } catch {
    // storage full or unavailable; the draft lives only in memory
  }
}

export function clearDraft(userId) {
  try {
    localStorage.removeItem(key(userId));
  } catch {
    // storage unavailable
  }
}

export function setDraftStore(userId, store) {
  const draft = loadDraft(userId) || emptyDraft();
  saveDraft(userId, { ...draft, mode: 'DIRECT', storeId: store.id, storeName: store.name });
}

export function hasContent(rows) {
  return rows.some((r) => r.name.trim());
}

function displayName(row) {
  const base = row.name.trim();
  const brand = (row.brand || row.selection?.brand || '').trim();
  if (brand && base && !base.toLowerCase().includes(brand.toLowerCase())) {
    return `${brand} ${base}`.slice(0, 80);
  }
  return base.slice(0, 80);
}

// Rows without a name are skipped; a blank quantity means 1.
export function cleanItems(rows) {
  const items = [];
  const errors = {};
  for (const row of rows) {
    const name = displayName(row);
    if (!name) continue;
    const text = String(row.quantity).trim().replace(',', '.');
    const quantity = text === '' ? 1 : Number(text);
    if (!Number.isFinite(quantity) || quantity <= 0) {
      errors[row.id] = 'err.qtyInvalid';
      continue;
    }
    items.push({ name, quantity, unit: UNITS.includes(row.unit) ? row.unit : DEFAULT_UNIT });
  }
  return { items, errors };
}

function rowsFromScanned(scanned, limit) {
  return scanned.slice(0, Math.max(0, limit)).map((item) => {
    const selection = item.selection && typeof item.selection === 'object' ? item.selection : {};
    const options = item.options && typeof item.options === 'object' ? item.options : null;
    const unitRaw = item.unit || selection.unit || DEFAULT_UNIT;
    const baseName = String(item.baseName || item.base_name || item.name || '').trim().slice(0, 80);
    const mode = item.mode || selection.mode || 'simple';
    const row = {
      ...newRow(),
      // Simple kirana name by default (Atta), not branded SKU titles.
      name: String(mode === 'advanced' ? item.name || baseName : baseName || item.name || '').trim().slice(0, 80),
      baseName,
      mode,
      quantity: item.quantity != null && Number(item.quantity) > 0 ? formatQty(item.quantity) : '',
      unit: UNITS.includes(unitRaw) ? unitRaw : DEFAULT_UNIT,
      brand: mode === 'advanced' ? item.brand || selection.brand || '' : '',
      department: mode === 'advanced' ? item.department || selection.department || '' : '',
      category: mode === 'advanced' ? item.category || selection.category || '' : '',
      type: mode === 'advanced' ? item.type || selection.type || '' : '',
      pack: mode === 'advanced' ? item.pack || selection.pack || '' : '',
      catalogueId: mode === 'advanced' ? item.catalogueId || item.catalogue_id || selection.catalogue_id || '' : '',
      mrp: item.mrp && typeof item.mrp === 'object' ? item.mrp : null,
      selection,
      options,
    };
    if (item.confident === false) row.unsure = true;
    return row;
  });
}

export function addScannedItems(userId, scanned) {
  const draft = loadDraft(userId) || emptyDraft();
  const kept = draft.items.filter((r) => r.name.trim());
  const rows = rowsFromScanned(scanned, MAX_ITEMS - kept.length);
  const items = [...kept, ...rows];
  saveDraft(userId, { ...draft, items: items.length ? items : emptyDraft().items, fromPhoto: true });
  return rows.length;
}

export function replaceWithScannedItems(userId, scanned) {
  const draft = loadDraft(userId) || emptyDraft();
  const rows = rowsFromScanned(scanned, MAX_ITEMS);
  saveDraft(userId, {
    ...draft,
    items: rows.length ? rows : emptyDraft().items,
    fromPhoto: true,
  });
  return rows.length;
}
