import { useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { useAuth } from './AuthContext.jsx';
import Field from '../components/Field.jsx';
import WaitNote from '../components/WaitNote.jsx';
import { useRateLimit } from '../lib/useRateLimit.js';
import { errorText } from '../components/ErrorBox.jsx';
import { cleanPhone, validatePassword, validatePhone } from '../lib/validate.js';
import { isDemoBuild } from '../lib/geo.js';
import { useT } from '../i18n/useT.js';

export default function Login() {
  const { t } = useT();
  const { login } = useAuth();
  const [phone, setPhone] = useState('');
  const [password, setPassword] = useState('');
  const demo = isDemoBuild();
  // Client errors are i18n keys; server field errors are the server's text.
  const [errors, setErrors] = useState({});
  const [serverFields, setServerFields] = useState({});
  const [formError, setFormError] = useState(null);
  const [busy, setBusy] = useState(false);
  const busyRef = useRef(false);
  // The server locks a number after too many wrong passwords; another number can still try.
  const limit = useRateLimit(phone.length === 10 ? `login.${phone}` : null);

  function clearField(name) {
    setErrors((e) => ({ ...e, [name]: null }));
    setServerFields((e) => ({ ...e, [name]: null }));
    setFormError(null);
  }

  async function onSubmit(e) {
    e.preventDefault();
    if (busyRef.current || limit.locked) return;

    const next = { phone: validatePhone(phone), password: validatePassword(password, { checkLength: false }) };
    setErrors(next);
    setServerFields({});
    setFormError(null);
    if (next.phone || next.password) return;

    busyRef.current = true;
    setBusy(true);
    try {
      await login(phone, password);
      // RequireGuest redirects to the role's home once the user is set.
    } catch (err) {
      if (limit.check(err)) return;
      setServerFields(err.fields || {});
      setFormError(err);
    } finally {
      busyRef.current = false;
      setBusy(false);
    }
  }

  const fieldError = (name) => (errors[name] ? t(errors[name]) : serverFields[name]);

  return (
    <section className="screen auth-screen">
      <h2>{t('login.title')}</h2>
      <form className="form" onSubmit={onSubmit} noValidate>
        <Field
          id="login-phone"
          label={t('field.phone')}
          type="tel"
          inputMode="numeric"
          autoComplete="tel-national"
          maxLength={10}
          placeholder={t('field.phonePlaceholder')}
          value={phone}
          error={fieldError('phone')}
          onChange={(e) => {
            setPhone(cleanPhone(e.target.value));
            clearField('phone');
          }}
        />
        <Field
          id="login-password"
          label={t('field.password')}
          type="password"
          autoComplete="current-password"
          value={password}
          error={fieldError('password')}
          onChange={(e) => {
            setPassword(e.target.value);
            clearField('password');
          }}
        />
        <WaitNote secondsLeft={limit.secondsLeft} />
        {formError && !limit.locked && (
          <p className="form-error" role="alert">
            {errorText(formError, t)}
          </p>
        )}
        <button type="submit" className="btn btn-block" disabled={busy || limit.locked} aria-busy={busy}>
          {busy && <span className="spinner" aria-hidden="true" />}
          {busy ? t('common.wait') : t('login.submit')}
        </button>
      </form>
      <p className="auth-switch">
        {t('login.noAccount')} <Link to="/register">{t('login.registerLink')}</Link>
      </p>
      {demo && (
        <div className="card note demo-logins" role="note">
          <p className="label">{t('demo.title')}</p>
          <button
            type="button"
            className="demo-fill"
            onClick={() => {
              setPhone('9000000001');
              setPassword('test123');
              setFormError(null);
            }}
          >
            {t('demo.customer')}
          </button>
          <button
            type="button"
            className="demo-fill"
            onClick={() => {
              setPhone('9000000011');
              setPassword('test123');
              setFormError(null);
            }}
          >
            {t('demo.shop')}
          </button>
          <p className="muted small">{t('demo.hint')}</p>
        </div>
      )}
    </section>
  );
}
