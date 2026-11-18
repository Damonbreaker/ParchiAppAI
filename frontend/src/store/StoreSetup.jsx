import { useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext.jsx';
import { useStore } from './StoreContext.jsx';
import { createStore, updateMyStore } from '../api/endpoints.js';
import Field from '../components/Field.jsx';
import LocationPicker from '../components/LocationPicker.jsx';
import { errorText } from '../components/ErrorBox.jsx';
import { cleanPhone, validatePhone } from '../lib/validate.js';
import { useT } from '../i18n/useT.js';

const required = (value) => (value.trim() ? null : 'err.required');

// mode="create" for first-time setup, mode="edit" to change an existing store.
export default function StoreSetup({ mode }) {
  const { t } = useT();
  const { user } = useAuth();
  const { store, setStore } = useStore();
  const navigate = useNavigate();
  const editing = mode === 'edit';

  const [form, setForm] = useState(() => ({
    name: editing ? store.name : '',
    address: editing ? store.address : '',
    area: editing ? store.area : '',
    phone: editing ? store.phone || '' : user.phone,
  }));
  const [location, setLocation] = useState(() =>
    editing ? { lat: store.lat, lng: store.lng, label: 'location.label.saved' } : null
  );
  // Client errors are i18n keys; server field errors are the server's text.
  const [errors, setErrors] = useState({});
  const [serverFields, setServerFields] = useState({});
  const [formError, setFormError] = useState(null);
  const [busy, setBusy] = useState(false);
  const busyRef = useRef(false);

  function setField(name, value) {
    setForm((f) => ({ ...f, [name]: value }));
    setErrors((e) => ({ ...e, [name]: null }));
    setServerFields((e) => ({ ...e, [name]: null }));
    setFormError(null);
  }

  function pickLocation(next) {
    setLocation(next);
    setErrors((e) => ({ ...e, location: null }));
    setServerFields((e) => ({ ...e, lat: null, lng: null }));
  }

  async function onSubmit(e) {
    e.preventDefault();
    if (busyRef.current) return;

    const next = {
      name: required(form.name),
      address: required(form.address),
      area: required(form.area),
      phone: form.phone ? validatePhone(form.phone) : null,
      location: location ? null : 'err.locationRequired',
    };
    setErrors(next);
    setServerFields({});
    setFormError(null);
    if (Object.values(next).some(Boolean)) return;

    const body = {
      name: form.name.trim(),
      address: form.address.trim(),
      area: form.area.trim(),
      phone: form.phone || null,
      lat: location.lat,
      lng: location.lng,
    };

    busyRef.current = true;
    setBusy(true);
    try {
      const saved = editing ? await updateMyStore(body) : await createStore(body);
      setStore(saved);
      navigate('/s/store', { replace: true });
    } catch (err) {
      setServerFields(err.fields || {});
      setFormError(err);
      busyRef.current = false;
      setBusy(false);
    }
  }

  const fieldError = (name) => (errors[name] ? t(errors[name]) : serverFields[name]);
  const locationError = errors.location ? t(errors.location) : serverFields.lat || serverFields.lng;

  return (
    <section className="screen">
      <h2>{t(editing ? 'store.editTitle' : 'store.setupTitle')}</h2>
      {!editing && <p className="muted">{t('store.setupSubtitle')}</p>}
      <form className="form" onSubmit={onSubmit} noValidate>
        <Field
          id="store-name"
          label={t('field.storeName')}
          value={form.name}
          error={fieldError('name')}
          onChange={(e) => setField('name', e.target.value)}
        />
        <Field
          id="store-address"
          label={t('field.address')}
          placeholder={t('field.addressPlaceholder')}
          autoComplete="street-address"
          value={form.address}
          error={fieldError('address')}
          onChange={(e) => setField('address', e.target.value)}
        />
        <Field
          id="store-area"
          label={t('field.area')}
          placeholder={t('field.areaPlaceholder')}
          value={form.area}
          error={fieldError('area')}
          onChange={(e) => setField('area', e.target.value)}
        />
        <Field
          id="store-phone"
          label={t('field.storePhone')}
          type="tel"
          inputMode="numeric"
          maxLength={10}
          value={form.phone}
          error={fieldError('phone')}
          onChange={(e) => setField('phone', cleanPhone(e.target.value))}
        />
        <div className="field">
          <p className="label">{t('field.location')}</p>
          <LocationPicker idPrefix="store-loc" value={location} onChange={pickLocation} error={locationError} />
        </div>
        {formError && (
          <p className="form-error" role="alert">
            {errorText(formError, t)}
          </p>
        )}
        <button type="submit" className="btn btn-block" disabled={busy} aria-busy={busy}>
          {busy && <span className="spinner" aria-hidden="true" />}
          {busy ? t('common.wait') : t(editing ? 'store.saveChanges' : 'store.create')}
        </button>
        {editing && (
          <button type="button" className="btn btn-secondary btn-block" onClick={() => navigate('/s/store')} disabled={busy}>
            {t('common.cancel')}
          </button>
        )}
      </form>
    </section>
  );
}
