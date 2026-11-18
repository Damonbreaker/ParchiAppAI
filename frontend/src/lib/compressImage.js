// Shrinks a phone photo before upload: longest side max 1280 px, JPEG quality 0.7
// (about 150-300 KB). Imported only by customer screens, so it stays in that chunk.
// Low-end phones run out of memory on 12 MP photos, so every decoded bitmap,
// canvas and object URL is released as soon as it is no longer needed.

const MAX_SIDE = 1280;
const QUALITY = 0.7;

export class ImageReadError extends Error {
  constructor() {
    super('This file is not an image the browser can read');
    this.code = 'UNREADABLE_IMAGE';
  }
}

// Returns { source, width, height, release } for drawImage.
async function decode(file) {
  if (typeof createImageBitmap === 'function') {
    try {
      // from-image applies the EXIF rotation, so phone photos aren't sideways.
      const bitmap = await createImageBitmap(file, { imageOrientation: 'from-image' });
      return { source: bitmap, width: bitmap.width, height: bitmap.height, release: () => bitmap.close() };
    } catch {
      // Old browsers reject the options object; fall back to <img> below.
    }
  }
  const url = URL.createObjectURL(file);
  const img = new Image();
  const release = () => {
    img.removeAttribute('src');
    URL.revokeObjectURL(url);
  };
  try {
    await new Promise((resolve, reject) => {
      img.onload = resolve;
      img.onerror = reject;
      img.src = url;
    });
  } catch {
    release();
    throw new ImageReadError();
  }
  // <img> honours EXIF orientation by default (CSS image-orientation: from-image).
  return { source: img, width: img.naturalWidth, height: img.naturalHeight, release };
}

function toBlob(canvas) {
  return new Promise((resolve, reject) =>
    canvas.toBlob((blob) => (blob ? resolve(blob) : reject(new ImageReadError())), 'image/jpeg', QUALITY)
  );
}

// -> { blob, width, height, originalBytes, compressedBytes }. Throws ImageReadError.
export async function compressImage(file) {
  if (file.type && !file.type.startsWith('image/')) throw new ImageReadError();

  const decoded = await decode(file);
  const { width: w, height: h } = decoded;
  if (!w || !h) {
    decoded.release();
    throw new ImageReadError();
  }
  const scale = Math.min(1, MAX_SIDE / Math.max(w, h));
  const width = Math.max(1, Math.round(w * scale));
  const height = Math.max(1, Math.round(h * scale));

  const canvas = document.createElement('canvas');
  let blob;
  try {
    canvas.width = width;
    canvas.height = height;
    const ctx = canvas.getContext('2d');
    ctx.fillStyle = '#fff'; // transparent PNG areas would turn black in JPEG
    ctx.fillRect(0, 0, width, height);
    ctx.drawImage(decoded.source, 0, 0, width, height);
    decoded.release();
    blob = await toBlob(canvas);
  } finally {
    decoded.release();
    canvas.width = 0;
    canvas.height = 0;
  }

  // Already a small JPEG that re-encoding made bigger: send the original.
  if (blob.size >= file.size && file.type === 'image/jpeg' && scale === 1) {
    blob = file;
  }
  return { blob, width, height, originalBytes: file.size, compressedBytes: blob.size };
}

// 4404019 -> "4.2 MB", 235000 -> "230 KB" (labels from i18n bytes.mb / bytes.kb).
export function formatBytes(bytes, t) {
  if (bytes >= 1024 * 1024) return t('bytes.mb', { n: (bytes / (1024 * 1024)).toFixed(1) });
  return t('bytes.kb', { n: Math.max(1, Math.round(bytes / 1024)) });
}
