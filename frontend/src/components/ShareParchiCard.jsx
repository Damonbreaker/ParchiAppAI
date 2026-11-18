import { useEffect, useRef, useState } from 'react';
import { getShareSnapshot, patchShareSnapshot } from '../lib/shareSnapshot.js';
import { formatINR } from '../lib/money.js';
import { useT } from '../i18n/useT.js';

const W = 1080;
const H = 1080;

function roundRect(ctx, x, y, w, h, r) {
  ctx.beginPath();
  ctx.moveTo(x + r, y);
  ctx.arcTo(x + w, y, x + w, y + h, r);
  ctx.arcTo(x + w, y + h, x, y + h, r);
  ctx.arcTo(x, y + h, x, y, r);
  ctx.arcTo(x, y, x + w, y, r);
  ctx.closePath();
}

function drawCard(ctx, { photo, items, quoteCount, lowestPrice, tagline, brand }) {
  const g = ctx.createLinearGradient(0, 0, W, H);
  g.addColorStop(0, '#163d2e');
  g.addColorStop(1, '#0f241c');
  ctx.fillStyle = g;
  ctx.fillRect(0, 0, W, H);

  ctx.fillStyle = '#fbf6ee';
  ctx.font = '700 42px system-ui, sans-serif';
  ctx.fillText(brand, 56, 72);
  ctx.fillStyle = 'rgba(251,246,238,0.75)';
  ctx.font = '500 28px system-ui, sans-serif';
  ctx.fillText(tagline, 56, 118);

  ctx.fillStyle = '#1f4a3a';
  roundRect(ctx, 48, 160, 460, 760, 28);
  ctx.fill();
  if (photo) {
    const scale = Math.max(460 / photo.width, 760 / photo.height);
    const dw = photo.width * scale;
    const dh = photo.height * scale;
    const dx = 48 + (460 - dw) / 2;
    const dy = 160 + (760 - dh) / 2;
    ctx.save();
    roundRect(ctx, 48, 160, 460, 760, 28);
    ctx.clip();
    ctx.drawImage(photo, dx, dy, dw, dh);
    ctx.restore();
  } else {
    ctx.fillStyle = 'rgba(251,246,238,0.55)';
    ctx.font = '600 34px system-ui, sans-serif';
    ctx.fillText('Handwritten', 140, 520);
    ctx.fillText('parchi', 190, 570);
  }
  ctx.fillStyle = '#fbf6ee';
  ctx.font = '700 26px system-ui, sans-serif';
  ctx.fillText('Handwritten list', 72, 950);

  ctx.fillStyle = '#fbf6ee';
  roundRect(ctx, 540, 160, 492, 760, 28);
  ctx.fill();
  ctx.fillStyle = '#163d2e';
  ctx.font = '700 34px system-ui, sans-serif';
  ctx.fillText('AI read it', 572, 230);
  ctx.font = '500 30px system-ui, sans-serif';
  let y = 290;
  const lines = items.length ? items : ['(items)'];
  for (const name of lines.slice(0, 10)) {
    ctx.fillText(`• ${String(name).slice(0, 28)}`, 572, y);
    y += 48;
  }
  if (items.length > 10) {
    ctx.fillStyle = 'rgba(22,61,46,0.65)';
    ctx.fillText(`+${items.length - 10} more`, 572, y);
  }

  if (quoteCount != null && quoteCount > 0) {
    const boxY = 820;
    ctx.fillStyle = '#1a6b4a';
    roundRect(ctx, 572, boxY, 428, 80, 20);
    ctx.fill();
    ctx.fillStyle = '#fbf6ee';
    ctx.font = '700 28px system-ui, sans-serif';
    const priceBit = lowestPrice != null ? ` · from ${formatINR(lowestPrice)}` : '';
    ctx.fillText(`${quoteCount} shops quoted${priceBit}`.slice(0, 34), 596, boxY + 50);
  }

  ctx.fillStyle = 'rgba(251,246,238,0.7)';
  ctx.font = '500 24px system-ui, sans-serif';
  ctx.fillText('If they can write it, we can shop it.', 56, 1030);
}

function loadImageFromBlob(blob) {
  return new Promise((resolve) => {
    if (!blob) {
      resolve(null);
      return;
    }
    const url = URL.createObjectURL(blob);
    const img = new Image();
    img.onload = () => {
      URL.revokeObjectURL(url);
      resolve(img);
    };
    img.onerror = () => {
      URL.revokeObjectURL(url);
      resolve(null);
    };
    img.src = url;
  });
}

/** Viral before/after card: handwritten parchi | AI list (+ optional quote race). */
export default function ShareParchiCard({ quoteCount = null, lowestPrice = null, compact = false }) {
  const { t } = useT();
  const canvasRef = useRef(null);
  const [previewUrl, setPreviewUrl] = useState(null);
  const [busy, setBusy] = useState(false);
  const [note, setNote] = useState(null);
  const snap = getShareSnapshot();
  const itemKey = (snap?.items || []).join('|');

  useEffect(() => {
    if (quoteCount != null || lowestPrice != null) {
      patchShareSnapshot({
        ...(quoteCount != null ? { quoteCount } : {}),
        ...(lowestPrice != null ? { lowestPrice } : {}),
      });
    }
  }, [quoteCount, lowestPrice]);

  useEffect(() => {
    let cancelled = false;
    const current = getShareSnapshot();
    if (!current?.items?.length && !current?.photoBlob) {
      setPreviewUrl(null);
      return undefined;
    }

    (async () => {
      const photo = await loadImageFromBlob(current.photoBlob);
      if (cancelled || !canvasRef.current) return;
      const canvas = canvasRef.current;
      canvas.width = W;
      canvas.height = H;
      drawCard(canvas.getContext('2d'), {
        photo,
        items: current.items || [],
        quoteCount: quoteCount ?? current.quoteCount,
        lowestPrice: lowestPrice ?? current.lowestPrice,
        tagline: t('share.tagline'),
        brand: t('app.name'),
      });
      if (!cancelled) setPreviewUrl(canvas.toDataURL('image/png'));
    })();

    return () => {
      cancelled = true;
    };
  }, [quoteCount, lowestPrice, t, itemKey]);

  if (!snap?.items?.length && !snap?.photoBlob) return null;

  async function share() {
    setBusy(true);
    setNote(null);
    try {
      const canvas = canvasRef.current;
      if (!canvas) return;
      const blob = await new Promise((resolve) => canvas.toBlob(resolve, 'image/png'));
      if (!blob) throw new Error('blob');
      const file = new File([blob], 'parchi-share.png', { type: 'image/png' });
      const text = t('share.caption', {
        n: snap.items?.length || 0,
        quotes: quoteCount ?? snap.quoteCount ?? 0,
      });

      if (navigator.canShare?.({ files: [file] })) {
        await navigator.share({ files: [file], title: t('app.name'), text });
        setNote(t('share.shared'));
        return;
      }
      if (navigator.share) {
        await navigator.share({ title: t('app.name'), text });
        setNote(t('share.sharedText'));
        return;
      }
      const a = document.createElement('a');
      a.href = previewUrl || canvas.toDataURL('image/png');
      a.download = 'parchi-share.png';
      a.click();
      try {
        await navigator.clipboard.writeText(text);
        setNote(t('share.downloadedCopied'));
      } catch {
        setNote(t('share.downloaded'));
      }
    } catch (err) {
      if (err?.name !== 'AbortError') setNote(t('share.failed'));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className={`card share-parchi ${compact ? 'share-parchi-compact' : ''}`}>
      <canvas ref={canvasRef} hidden aria-hidden="true" />
      <p className="label">{t('share.title')}</p>
      <p className="muted small">{t('share.hint')}</p>
      {previewUrl && <img src={previewUrl} alt={t('share.previewAlt')} className="share-parchi-preview" />}
      <button type="button" className="btn btn-block" onClick={share} disabled={busy || !previewUrl}>
        {busy ? t('share.sharing') : t('share.cta')}
      </button>
      {note && (
        <p className="muted small" role="status">
          {note}
        </p>
      )}
    </div>
  );
}
