import { useAuth } from '../auth/AuthContext.jsx';
import { useT } from '../i18n/useT.js';

// Language names are shown in their own script, so they are not translated.
const LANGS = [
  ['en', 'EN'],
  ['hi', 'हिं'],
];

export default function LangToggle({ className = '' }) {
  const { t, lang } = useT();
  const { changeLanguage } = useAuth();
  return (
    <div className={`lang-toggle ${className}`} role="group" aria-label={t('account.language')}>
      {LANGS.map(([code, label]) => (
        <button
          key={code}
          type="button"
          lang={code}
          aria-pressed={lang === code}
          onClick={() => changeLanguage(code)}
        >
          {label}
        </button>
      ))}
    </div>
  );
}
