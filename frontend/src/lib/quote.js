import { formatQty, UNITS } from './units.js';

// Store quote editor helpers. The rules mirror the backend's QuoteService:
//   packingTimeMins: whole number 5..240
//   AVAILABLE / PARTIAL lines: offeredQty > 0 (max 7 digits, 3 decimals),
//                              unit required, linePrice > 0 (max 6 digits, 2 decimals)
//   UNAVAILABLE lines: nothing offered, nothing charged
//   at least one line must be AVAILABLE or PARTIAL
export const STATUSES = ['AVAILABLE', 'PARTIAL', 'UNAVAILABLE'];
export const PACKING_CHIPS = [10, 15, 30, 45, 60];
export const PACKING_MIN = 5;
export const PACKING_MAX = 240;

// "1,200" / " ₹ 250.5 " -> "1200" / "250.5"
export function cleanNumber(text) {
  return String(text ?? '').replace(/[\s,₹]/g, '');
}

// Checks a decimal string against the server's digit limits.
// Returns null when fine, else 'required' | 'invalid' | 'decimals' | 'large'.
function checkDecimal(text, intDigits, fracDigits) {
  const s = cleanNumber(text);
  if (s === '') return 'required';
  const m = s.match(/^(\d*)(?:\.(\d*))?$/);
  if (!m || (m[1] === '' && !m[2])) return 'invalid';
  const intPart = m[1].replace(/^0+/, '');
  const fracPart = (m[2] || '').replace(/0+$/, '');
  if (!/[1-9]/.test(intPart + fracPart)) return 'invalid'; // zero
  if (fracPart.length > fracDigits) return 'decimals';
  if (intPart.length > intDigits) return 'large';
  return null;
}

const QTY_ERR = { required: 'quote.err.qty', invalid: 'quote.err.qty', decimals: 'quote.err.qtyDecimals', large: 'quote.err.qtyLarge' };
const PRICE_ERR = {
  required: 'quote.err.price',
  invalid: 'quote.err.price',
  decimals: 'quote.err.priceDecimals',
  large: 'quote.err.priceLarge',
};

export function qtyError(text) {
  const e = checkDecimal(text, 7, 3);
  return e ? QTY_ERR[e] : null;
}

export function priceError(text) {
  const e = checkDecimal(text, 6, 2);
  return e ? PRICE_ERR[e] : null;
}

// Valid price text -> integer paise, else null.
export function toPaise(text) {
  if (priceError(text)) return null;
  return Math.round(Number(cleanNumber(text)) * 100);
}

export function packingError(text) {
  const s = cleanNumber(text);
  if (!/^\d+$/.test(s)) return 'quote.err.packing';
  const n = Number(s);
  return n >= PACKING_MIN && n <= PACKING_MAX ? null : 'quote.err.packing';
}

// For prefilling inputs from the server: 280.5 -> "280.50", 300 -> "300".
function priceText(value) {
  const n = Number(value);
  if (!Number.isFinite(n) || n <= 0) return '';
  return Number.isInteger(n) ? String(n) : n.toFixed(2);
}

const sameQty = (a, b) => Number(a) === Number(b);

// One editor row per request item, prefilled from myQuote when there is one.
// Row: { id, name, askedQty, askedUnit, status, qty, unit, price, qtyOpen }
export function buildRows(items, myQuote) {
  const lines = new Map((myQuote?.items || []).map((l) => [l.requestItemId, l]));
  return items.map((item) => {
    const askedQty = formatQty(item.quantity);
    const base = {
      id: item.id,
      name: item.name,
      askedQty,
      askedUnit: item.unit,
      status: 'AVAILABLE',
      qty: askedQty,
      unit: item.unit,
      price: '',
      qtyOpen: false,
    };
    const line = lines.get(item.id);
    if (!line) return base;
    if (line.status === 'UNAVAILABLE') return { ...base, status: 'UNAVAILABLE' };
    const qty = formatQty(line.offeredQty);
    const unit = UNITS.includes(line.unit) ? line.unit : item.unit;
    const changed = !sameQty(qty, askedQty) || unit !== item.unit;
    return {
      ...base,
      status: line.status,
      qty,
      unit,
      price: priceText(line.linePrice),
      qtyOpen: line.status === 'AVAILABLE' && changed,
    };
  });
}

// Tapping a status button. Available goes back to exactly what was asked;
// Partial opens the quantity; Unavailable keeps typed values but hides them.
export function withStatus(row, status) {
  if (status === row.status) return row;
  if (status === 'AVAILABLE') return { ...row, status, qty: row.askedQty, unit: row.askedUnit, qtyOpen: false };
  return { ...row, status };
}

export const qtyEditable = (row) => row.status === 'PARTIAL' || row.qtyOpen;

// { qty, price } i18n keys (or null) for one row.
export function rowErrors(row) {
  if (row.status === 'UNAVAILABLE') return NO_ERRORS;
  const qty = qtyError(row.qty) || (UNITS.includes(row.unit) ? null : 'quote.err.unit');
  const price = priceError(row.price);
  return qty || price ? { qty, price } : NO_ERRORS;
}
export const NO_ERRORS = Object.freeze({ qty: null, price: null });

export function totalPaise(rows) {
  let sum = 0;
  for (const row of rows) {
    if (row.status === 'UNAVAILABLE') continue;
    const p = toPaise(row.price);
    if (p !== null) sum += p;
  }
  return sum;
}

export const allUnavailable = (rows) => rows.every((r) => r.status === 'UNAVAILABLE');

export function toPayload(rows, packing) {
  return {
    packingTimeMins: Number(cleanNumber(packing)),
    items: rows.map((row) =>
      row.status === 'UNAVAILABLE'
        ? { requestItemId: row.id, status: row.status, unit: row.askedUnit }
        : {
            requestItemId: row.id,
            status: row.status,
            offeredQty: Number(cleanNumber(row.qty)),
            unit: row.unit,
            linePrice: Number(cleanNumber(row.price)),
          }
    ),
  };
}

// Server field errors ("items[3].linePrice") -> { [rowId]: { qty, price } }.
export function mapServerErrors(fields, rows) {
  const out = {};
  let packing = null;
  for (const key of Object.keys(fields || {})) {
    if (key === 'packingTimeMins') {
      packing = 'quote.err.packing';
      continue;
    }
    const m = key.match(/^items\[(\d+)\]\.(\w+)$/);
    const row = m && rows[Number(m[1])];
    if (!row) continue;
    const slot = m[2] === 'linePrice' ? 'price' : 'qty';
    out[row.id] = { ...(out[row.id] || NO_ERRORS), [slot]: 'quote.err.server' };
  }
  return { rows: out, packing };
}

// Asked vs offered, for the customer sheet and the store summary.
export function isChangedLine(line) {
  return line.status !== 'UNAVAILABLE' && (!sameQty(line.askedQty, line.offeredQty) || line.askedUnit !== line.unit);
}

// The store's own quote lines + request items -> the customer-style line shape.
export function mergeLines(items, myQuote) {
  const lines = new Map((myQuote?.items || []).map((l) => [l.requestItemId, l]));
  return items.map((item) => {
    const l = lines.get(item.id) || {};
    return {
      requestItemId: item.id,
      name: item.name,
      askedQty: item.quantity,
      askedUnit: item.unit,
      offeredQty: l.offeredQty,
      unit: l.unit,
      linePrice: l.linePrice,
      status: l.status,
    };
  });
}
