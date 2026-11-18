import { useEffect, useState } from 'react';
import { randomFact } from '../lib/groceryFacts.js';
import { useT } from '../i18n/useT.js';

/** Rotating grocery facts while the customer waits (OCR, quotes, etc.). */
export default function FactTicker({ className = '' }) {
  const { t, lang } = useT();
  const [fact, setFact] = useState(() => randomFact(lang));

  useEffect(() => {
    setFact(randomFact(lang));
    let timer;
    const schedule = () => {
      const ms = 2000 + Math.floor(Math.random() * 2000); // 2–4s
      timer = window.setTimeout(() => {
        setFact((prev) => randomFact(lang, prev));
        schedule();
      }, ms);
    };
    schedule();
    return () => window.clearTimeout(timer);
  }, [lang]);

  return (
    <div className={`fact-ticker ${className}`.trim()} role="status" aria-live="polite">
      <p className="fact-ticker-label">{t('facts.label')}</p>
      <p className="fact-ticker-text">{fact}</p>
    </div>
  );
}
