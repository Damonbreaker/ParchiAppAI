// Date formatting with Intl only, in the current UI language.
const LOCALES = { en: 'en-IN', hi: 'hi-IN' };
const cache = new Map();

function formatter(kind, lang, make) {
  const key = `${kind}:${lang}`;
  if (!cache.has(key)) cache.set(key, make(LOCALES[lang] || LOCALES.en));
  return cache.get(key);
}

// "5 min ago" / "5 मिनट पहले"
export function timeAgo(iso, lang, now = Date.now()) {
  const rtf = formatter('rel', lang, (locale) => new Intl.RelativeTimeFormat(locale, { numeric: 'auto', style: 'short' }));
  const seconds = (new Date(iso).getTime() - now) / 1000;
  const abs = Math.abs(seconds);
  if (abs < 45) return rtf.format(0, 'second');
  if (abs < 3600) return rtf.format(Math.round(seconds / 60), 'minute');
  if (abs < 86400) return rtf.format(Math.round(seconds / 3600), 'hour');
  return rtf.format(Math.round(seconds / 86400), 'day');
}

// "7:45 pm" today, "12 Nov, 7:45 pm" on any other day.
export function formatTime(iso, lang, now = new Date()) {
  const d = new Date(iso);
  const sameDay = d.toDateString() === now.toDateString();
  if (!sameDay) return formatDateTime(iso, lang);
  const tf = formatter('time', lang, (locale) => new Intl.DateTimeFormat(locale, { hour: 'numeric', minute: '2-digit' }));
  return tf.format(d);
}

// "11 Nov, 4:05 pm" / "11 नव॰, 4:05 pm"
export function formatDateTime(iso, lang) {
  const dtf = formatter(
    'dt',
    lang,
    (locale) => new Intl.DateTimeFormat(locale, { day: 'numeric', month: 'short', hour: 'numeric', minute: '2-digit' })
  );
  return dtf.format(new Date(iso));
}
