import { memo, useRef } from 'react';
import CataloguePolls from './CataloguePolls.jsx';
import { UNITS } from '../lib/units.js';
import { useT } from '../i18n/useT.js';

function ItemRow({ row, index, error, onChange, onDelete, onQtyEnter, registerName, onRowUpdate }) {
  const { t } = useT();
  const qtyRef = useRef(null);
  const errorId = `item-${row.id}-error`;
  // Show advanced toggle whenever scan attached catalogue options (even if collapsed).
  const showCatalogue = Boolean(row.options || row.mrp || row.brand || row.catalogueId || row.baseName);

  return (
    <li className={`item-row${error ? ' has-error' : row.unsure ? ' is-unsure' : ''}`}>
      <div className="item-row-top">
        <input
          ref={(el) => registerName(row.id, el)}
          className="input item-name"
          aria-label={t('newList.itemName', { n: index + 1 })}
          placeholder={t('newList.namePlaceholder')}
          maxLength={80}
          enterKeyHint="next"
          value={row.name}
          onChange={(e) => onChange(row.id, 'name', e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter') {
              e.preventDefault();
              qtyRef.current?.focus();
            }
          }}
        />
        <button
          type="button"
          className="icon-btn"
          aria-label={t('newList.deleteItem', { n: index + 1 })}
          onClick={() => onDelete(row.id)}
        >
          <svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true">
            <path d="M6 6l12 12M18 6L6 18" />
          </svg>
        </button>
      </div>
      <div className="item-row-bottom">
        <input
          ref={qtyRef}
          className="input item-qty"
          inputMode="decimal"
          aria-label={t('newList.quantity')}
          aria-invalid={error ? true : undefined}
          aria-describedby={error ? errorId : undefined}
          placeholder="1"
          maxLength={8}
          enterKeyHint="next"
          value={row.quantity}
          onChange={(e) => onChange(row.id, 'quantity', e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter') {
              e.preventDefault();
              onQtyEnter(row.id);
            }
          }}
        />
        <select
          className="input item-unit"
          aria-label={t('newList.unit')}
          value={row.unit}
          onChange={(e) => onChange(row.id, 'unit', e.target.value)}
        >
          {UNITS.map((u) => (
            <option key={u} value={u}>
              {t(`unit.${u}`)}
            </option>
          ))}
        </select>
      </div>
      {showCatalogue && onRowUpdate ? <CataloguePolls row={row} onRowUpdate={onRowUpdate} /> : null}
      {error ? (
        <p id={errorId} className="field-error">
          {t(error)}
        </p>
      ) : row.unsure ? (
        <p className="unsure-hint">{t('scan.checkRow')}</p>
      ) : null}
    </li>
  );
}

export default memo(ItemRow);
