// Units the backend accepts. Labels live in i18n under unit.<UNIT>.
export const UNITS = ['PCS', 'G', 'KG', 'ML', 'L', 'PACKET', 'DOZEN'];
export const DEFAULT_UNIT = 'PCS';

// 0.5 -> "0.5", 2 -> "2", 0.3333333 -> "0.333"
export function formatQty(quantity) {
  return String(Math.round(Number(quantity) * 1000) / 1000);
}

// "2 kg" / "2 किलो"
export function formatItemQty(item, t) {
  return `${formatQty(item.quantity)} ${t(`unit.${item.unit}`)}`;
}
