import { createContext, createElement, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import en from './en.json';
import hi from './hi.json';

const DICTS = { en, hi };
const LANG_KEY = 'kirana.lang';

function readLang() {
  try {
    const saved = localStorage.getItem(LANG_KEY);
    if (saved && DICTS[saved]) return saved;
  } catch {
    // storage unavailable
  }
  return 'en';
}

const I18nContext = createContext(null);

export function I18nProvider({ children }) {
  const [lang, setLangState] = useState(readLang);

  const setLang = useCallback((next) => {
    if (!DICTS[next]) return;
    setLangState(next);
    try {
      localStorage.setItem(LANG_KEY, next);
    } catch {
      // storage unavailable
    }
  }, []);

  useEffect(() => {
    document.documentElement.lang = lang;
  }, [lang]);

  // t('nav.home') or t('home.greeting', { name }) for "{name}" placeholders.
  // Falls back to English, then to the key itself so a missing string is visible.
  const t = useCallback(
    (key, vars) => {
      let str = DICTS[lang][key] ?? en[key] ?? key;
      if (vars) for (const k in vars) str = str.split(`{${k}}`).join(String(vars[k]));
      return str;
    },
    [lang]
  );

  // tn('requests.sentTo', 3) picks requests.sentTo.one / .other and passes {n}.
  const tn = useCallback(
    (key, n, vars) => {
      const form = new Intl.PluralRules(lang === 'hi' ? 'hi-IN' : 'en-IN').select(n) === 'one' ? 'one' : 'other';
      return t(`${key}.${form}`, { n, ...vars });
    },
    [t, lang]
  );

  const value = useMemo(() => ({ t, tn, lang, setLang }), [t, tn, lang, setLang]);
  return createElement(I18nContext.Provider, { value }, children);
}

export function useT() {
  const ctx = useContext(I18nContext);
  if (!ctx) throw new Error('useT must be used inside <I18nProvider>');
  return ctx;
}
