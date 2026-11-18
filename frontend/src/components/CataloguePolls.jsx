import { useRef, useState } from 'react';
import { resolveCatalogue } from '../api/endpoints.js';
import { useT } from '../i18n/useT.js';

const CASCADE = {
  department: ['category', 'type', 'brand', 'product', 'catalogue_id'],
  category: ['type', 'brand', 'product', 'catalogue_id'],
  type: ['brand', 'product', 'catalogue_id'],
  brand: ['product', 'catalogue_id'],
  product: ['catalogue_id'],
};

function packLabel(pack) {
  if (!pack || typeof pack !== 'object') return '';
  const bits = [pack.pack_display || pack.pack || pack.label || pack.product || '']
    .concat(pack.brand ? [pack.brand] : [])
    .filter(Boolean);
  if (pack.mrp != null) bits.push(`₹${pack.mrp}`);
  return bits.join(' · ') || String(pack.catalogue_id || '');
}

function applyResolved(row, data) {
  const selection = data.selection || {};
  const options = data.options || null;
  const mode = data.mode || selection.mode || 'advanced';
  const next = {
    ...row,
    name: String(data.name || data.base_name || row.name || '').slice(0, 80),
    baseName: data.base_name || selection.base_name || row.baseName || '',
    mode,
    brand: data.brand || selection.brand || '',
    department: data.department || selection.department || '',
    category: data.category || selection.category || '',
    type: data.type || selection.type || '',
    pack: data.pack || selection.pack || '',
    catalogueId: data.catalogue_id || selection.catalogue_id || row.catalogueId || '',
    mrp: data.mrp || row.mrp || null,
    selection,
    options,
  };
  if (data.unit_hint && data.unit_hint !== row.unit) {
    next.unit = String(data.unit_hint).toUpperCase();
  }
  return next;
}

/**
 * Hidden by default. Opens only when customer/shopkeeper asks for brand/pack detail.
 */
export default function CataloguePolls({ row, onRowUpdate }) {
  const { t } = useT();
  const [open, setOpen] = useState(row.mode === 'advanced');
  const [busy, setBusy] = useState(false);
  const seq = useRef(0);

  const options = row.options || {};
  const selection = row.selection || {};
  const hasAny =
    (options.brands && options.brands.length) ||
    (options.products && options.products.length) ||
    (options.packs && options.packs.length) ||
    (options.types && options.types.length);

  if (!hasAny && !row.catalogueId && !row.brand && !row.options) return null;

  async function resolve(nextSelection, advanced = true) {
    const token = ++seq.current;
    setBusy(true);
    try {
      const data = await resolveCatalogue({
        advanced,
        row: {
          item: row.baseName || row.name,
          name: row.baseName || row.name,
          quantity: row.quantity ? `${row.quantity} ${row.unit || ''}`.trim() : '',
          catalogue_selection: nextSelection,
        },
        selections: { ...nextSelection, ...(advanced ? { advanced: true } : {}) },
      });
      if (token !== seq.current) return;
      onRowUpdate(row.id, applyResolved({ ...row, selection: nextSelection }, data));
    } catch {
      if (token !== seq.current) return;
      onRowUpdate(row.id, { ...row, selection: nextSelection, mode: advanced ? 'advanced' : 'simple' });
    } finally {
      if (token === seq.current) setBusy(false);
    }
  }

  function onPoll(key, value) {
    const next = { ...selection, [key]: value, advanced: true };
    for (const clear of CASCADE[key] || []) delete next[clear];
    if (!value) delete next[key];
    onRowUpdate(row.id, { ...row, selection: next, mode: 'advanced' });
    resolve(next, true);
  }

  function openAdvanced() {
    setOpen(true);
    resolve({ ...selection, advanced: true }, true);
  }

  function keepSimple() {
    setOpen(false);
    const next = {
      base_name: row.baseName || row.name,
      product: row.baseName || row.name,
      unit: selection.unit || row.unit || '',
      mode: 'simple',
    };
    onRowUpdate(row.id, {
      ...row,
      name: row.baseName || row.name,
      brand: '',
      pack: '',
      catalogueId: '',
      mode: 'simple',
      selection: next,
    });
  }

  const summary = [row.brand, row.type, row.pack].filter(Boolean).join(' · ');
  const mrp = row.mrp && row.mrp.amount != null ? row.mrp : null;
  const mrpMin = mrp?.amount_min != null ? Number(mrp.amount_min) : null;
  const mrpMax = mrp?.amount_max != null ? Number(mrp.amount_max) : null;
  const mrpPrice =
    Number.isFinite(mrpMin) && Number.isFinite(mrpMax) && mrpMax - mrpMin >= 1
      ? `₹${mrpMin.toFixed(0)}–₹${mrpMax.toFixed(0)}`
      : mrp
        ? `₹${Number(mrp.amount).toFixed(0)}`
        : '';

  return (
    <div className="catalogue-polls">
      {mrp && mrpPrice && (
        <p className="mrp-hint muted small">
          {t('catalogue.mrpEstimate', { price: mrpPrice })}
          {mrp.label ? ` · ${mrp.label}` : ''}
          {Number(mrp.variant_count) > 1 ? ` · ${t('catalogue.mrpVariants', { n: Number(mrp.variant_count) })}` : ''}
        </p>
      )}
      {!open ? (
        <button type="button" className="link-btn catalogue-toggle" onClick={openAdvanced}>
          {t('catalogue.needAdvanced')}
        </button>
      ) : (
        <>
          <button type="button" className="link-btn catalogue-toggle" onClick={keepSimple}>
            {t('catalogue.hideAdvanced')}
            {summary ? <span className="muted small"> · {summary}</span> : null}
          </button>
          <div className={`poll-panel${busy ? ' is-busy' : ''}`}>
            <p className="muted small">{t('catalogue.hint')}</p>
            <PollSelect
              label={t('catalogue.department')}
              value={selection.department || ''}
              choices={options.departments}
              onChange={(v) => onPoll('department', v)}
            />
            <PollSelect
              label={t('catalogue.category')}
              value={selection.category || ''}
              choices={options.categories}
              onChange={(v) => onPoll('category', v)}
            />
            <PollSelect
              label={t('catalogue.type')}
              value={selection.type || ''}
              choices={options.types}
              onChange={(v) => onPoll('type', v)}
            />
            <PollSelect
              label={t('catalogue.brand')}
              value={selection.brand || ''}
              choices={options.brands}
              onChange={(v) => onPoll('brand', v)}
            />
            <PollSelect
              label={t('catalogue.product')}
              value={selection.product || ''}
              choices={options.products}
              onChange={(v) => onPoll('product', v)}
            />
            <label className="poll-field">
              <span>{t('catalogue.pack')}</span>
              <select
                className="input"
                value={selection.catalogue_id || ''}
                onChange={(e) => onPoll('catalogue_id', e.target.value)}
              >
                <option value="">{t('catalogue.any')}</option>
                {(options.packs || []).map((pack) => (
                  <option key={pack.catalogue_id || packLabel(pack)} value={pack.catalogue_id || ''}>
                    {packLabel(pack)}
                  </option>
                ))}
              </select>
            </label>
            <PollSelect
              label={t('catalogue.unit')}
              value={selection.unit || ''}
              choices={options.units}
              onChange={(v) => onPoll('unit', v)}
              anyLabel={t('catalogue.keepUnit')}
            />
          </div>
        </>
      )}
    </div>
  );
}

function PollSelect({ label, value, choices, onChange, anyLabel }) {
  const { t } = useT();
  if (!choices || !choices.length) return null;
  return (
    <label className="poll-field">
      <span>{label}</span>
      <select className="input" value={value || ''} onChange={(e) => onChange(e.target.value)}>
        <option value="">{anyLabel || t('catalogue.any')}</option>
        {choices.map((c) => (
          <option key={c} value={c}>
            {c}
          </option>
        ))}
      </select>
    </label>
  );
}
