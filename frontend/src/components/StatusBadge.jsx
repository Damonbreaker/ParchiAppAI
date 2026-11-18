import { useT } from '../i18n/useT.js';

// Request status: DRAFT | OPEN | ACCEPTED | READY | PICKED_UP | CANCELLED
export default function StatusBadge({ status }) {
  const { t } = useT();
  const label = t(`status.${status}`);
  return <span className={`badge badge-status status-${String(status).toLowerCase()}`}>{label}</span>;
}
