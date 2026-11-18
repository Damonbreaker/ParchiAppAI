// Client-side checks. Each returns an i18n key, or null when valid.
export const PHONE_RE = /^[6-9]\d{9}$/;
export const MIN_PASSWORD = 6;

export function validateName(value) {
  return value.trim() ? null : 'err.nameRequired';
}

export function validatePhone(value) {
  if (!value) return 'err.phoneRequired';
  return PHONE_RE.test(value) ? null : 'err.phoneInvalid';
}

export function validatePassword(value, { checkLength = true } = {}) {
  if (!value) return 'err.passwordRequired';
  if (checkLength && value.length < MIN_PASSWORD) return 'err.passwordShort';
  return null;
}

// Keep only digits, max 10, so pasted "+91 98765-43210" style input stays usable.
export function cleanPhone(value) {
  const digits = value.replace(/\D/g, '');
  return (digits.length > 10 && digits.startsWith('91') ? digits.slice(2) : digits).slice(0, 10);
}
