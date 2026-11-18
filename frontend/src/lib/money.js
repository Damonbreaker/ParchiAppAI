// Rupees, always en-IN grouping (₹1,24,500). Whole amounts drop the paise.
const whole = new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 0 });
const exact = new Intl.NumberFormat('en-IN', {
  style: 'currency',
  currency: 'INR',
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
});

// formatINR(540) -> "₹540", formatINR("540.5") -> "₹540.50"
export function formatINR(amount) {
  const n = Number(amount);
  if (amount === null || amount === undefined || !Number.isFinite(n)) return '';
  return formatPaise(Math.round(n * 100));
}

// Integer paise in, so running totals never drift with float maths.
export function formatPaise(paise) {
  return paise % 100 === 0 ? whole.format(paise / 100) : exact.format(paise / 100);
}

/** Catalogue MRP estimate: single price, or min–max when many brand/pack variants exist. */
export function formatMrpEstimate(mrp) {
  if (!mrp || mrp.amount == null) return '';
  const min = mrp.amount_min != null ? Number(mrp.amount_min) : null;
  const max = mrp.amount_max != null ? Number(mrp.amount_max) : null;
  const mid = Number(mrp.amount);
  if (Number.isFinite(min) && Number.isFinite(max) && max - min >= 1) {
    return `${formatINR(min)}–${formatINR(max)}`;
  }
  return formatINR(mid);
}
