import { useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { useAuth } from './AuthContext.jsx';
import Field from '../components/Field.jsx';
import { errorText } from '../components/ErrorBox.jsx';
import { cleanPhone, validateName, validatePassword, validatePhone } from '../lib/validate.js';
import { useT } from '../i18n/useT.js';

const ROLES = [
  { role: 'CUSTOMER', icon: '🛒', title: 'register.roleCustomer', hint: 'register.roleCustomerHint' },
  { role: 'STORE_OWNER', icon: '🏪', title: 'register.roleStore', hint: 'register.roleStoreHint' },
];

function RolePicker({ onPick }) {
  const { t } = useT();
  return (
    <>
      <p className="label">{t('register.chooseRole')}</p>
      <div className="role-cards">
        {ROLES.map((r) => (
          <button key={r.role} type="button" className="role-card" onClick={() => onPick(r.role)}>
            <span className="role-icon" aria-hidden="true">
              {r.icon}
            </span>
            <span className="role-title">{t(r.title)}</span>
            <span className="muted small">{t(r.hint)}</span>
          </button>
        ))}
      </div>
    </>
  );
}

export default function Register() {
  const { t } = useT();
  const { register } = useAuth();
  const [role, setRole] = useState(null);
  const [form, setForm] = useState({ name: '', phone: '', password: '' });
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

  async function onSubmit(e) {
    e.preventDefault();
    if (busyRef.current) return;

    const next = {
      name: validateName(form.name),
      phone: validatePhone(form.phone),
      password: validatePassword(form.password),
    };
    setErrors(next);
    setServerFields({});
    setFormError(null);
    if (next.name || next.phone || next.password) return;

    busyRef.current = true;
    setBusy(true);
    try {
      await register({ name: form.name.trim(), phone: form.phone, password: form.password, role });
      // RequireGuest redirects to the role's home once the user is set.
    } catch (err) {
      if (err.code === 'PHONE_TAKEN') {
        setErrors({ phone: 'apiError.PHONE_TAKEN' });
      } else {
        setServerFields(err.fields || {});
        setFormError(err);
      }
    } finally {
      busyRef.current = false;
      setBusy(false);
    }
  }

  const fieldError = (name) => (errors[name] ? t(errors[name]) : serverFields[name]);
  const picked = ROLES.find((r) => r.role === role);

  return (
    <section className="screen auth-screen">
      <h2>{t('register.title')}</h2>
      {!picked ? (
        <RolePicker onPick={setRole} />
      ) : (
        <form className="form" onSubmit={onSubmit} noValidate>
          <div className="picked-role">
            <span>
              <span aria-hidden="true">{picked.icon}</span> {t(picked.title)}
            </span>
            <button type="button" className="link-btn" onClick={() => setRole(null)} disabled={busy}>
              {t('register.change')}
            </button>
          </div>
          <Field
            id="reg-name"
            label={t('field.name')}
            autoComplete="name"
            value={form.name}
            error={fieldError('name')}
            onChange={(e) => setField('name', e.target.value)}
          />
          <Field
            id="reg-phone"
            label={t('field.phone')}
            type="tel"
            inputMode="numeric"
            autoComplete="tel-national"
            maxLength={10}
            placeholder={t('field.phonePlaceholder')}
            value={form.phone}
            error={fieldError('phone')}
            onChange={(e) => setField('phone', cleanPhone(e.target.value))}
          />
          <Field
            id="reg-password"
            label={t('field.password')}
            type="password"
            autoComplete="new-password"
            placeholder={t('field.passwordPlaceholder')}
            value={form.password}
            error={fieldError('password')}
            onChange={(e) => setField('password', e.target.value)}
          />
          {formError && (
            <p className="form-error" role="alert">
              {errorText(formError, t)}
            </p>
          )}
          <button type="submit" className="btn btn-block" disabled={busy} aria-busy={busy}>
            {busy && <span className="spinner" aria-hidden="true" />}
            {busy ? t('common.wait') : t('register.submit')}
          </button>
        </form>
      )}
      <p className="auth-switch">
        {t('register.haveAccount')} <Link to="/login">{t('register.loginLink')}</Link>
      </p>
    </section>
  );
}
