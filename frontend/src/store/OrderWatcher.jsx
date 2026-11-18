import { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { getStoreRequests } from '../api/endpoints.js';
import Toast from '../components/Toast.jsx';
import { formatINR } from '../lib/money.js';
import { usePolling } from '../lib/usePolling.js';
import { useT } from '../i18n/useT.js';

const POLL_MS = 5000;

// Watches the ORDERS list on every store screen. The first load only records
// what is already there; any order id seen after that gets a toast + vibration.
export default function OrderWatcher() {
  const { t } = useT();
  const navigate = useNavigate();
  const known = useRef(null); // Set of order ids, null until the first load
  const busy = useRef(false);
  const [toast, setToast] = useState(null);

  const check = useCallback(() => {
    if (busy.current) return;
    busy.current = true;
    getStoreRequests('ORDERS')
      .then((res) => {
        const orders = (res && res.items) || [];
        if (known.current) {
          const fresh = orders.filter((o) => !known.current.has(o.id));
          if (fresh.length) {
            const o = fresh[0];
            setToast({
              id: o.id,
              text:
                fresh.length === 1
                  ? t('toast.newOrder', { name: o.customerName, price: formatINR(o.myQuoteTotal) })
                  : t('toast.newOrders', { n: fresh.length }),
              to: fresh.length === 1 ? `/s/requests/${o.id}` : '/s?tab=ORDERS',
            });
            try {
              navigator.vibrate?.(200);
            } catch {
              // some browsers throw without a user gesture; the toast is enough
            }
          }
        }
        known.current = new Set(orders.map((o) => o.id));
      })
      .catch(() => {
        // A missed check is fine; the next poll tries again.
      })
      .finally(() => {
        busy.current = false;
      });
  }, [t]);

  useEffect(() => {
    check();
  }, [check]);
  usePolling(check, POLL_MS);

  const close = useCallback(() => setToast(null), []);
  const open = useCallback(() => {
    if (toast) navigate(toast.to);
    setToast(null);
  }, [toast, navigate]);

  return <Toast toast={toast} onAction={open} onClose={close} />;
}
