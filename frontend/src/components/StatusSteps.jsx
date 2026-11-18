import { formatTime } from '../lib/time.js';
import { useT } from '../i18n/useT.js';

const STEPS = [
  { status: 'ACCEPTED', key: 'order.stepAccepted', at: 'acceptedAt' },
  { status: 'READY', key: 'order.stepReady', at: 'readyAt' },
  { status: 'PICKED_UP', key: 'order.stepPickedUp', at: 'pickedUpAt' },
];

// Accepted → Ready → Picked up, with the time under each finished step.
// `order` has status + acceptedAt / readyAt / pickedUpAt (customer and store views both do).
export default function StatusSteps({ order }) {
  const { t, lang } = useT();
  const current = STEPS.findIndex((s) => s.status === order.status);

  return (
    <ol className="steps" aria-label={t('order.progress')}>
      {STEPS.map((step, i) => {
        const done = i <= current;
        const at = order[step.at];
        return (
          <li
            key={step.status}
            className={done ? 'step is-done' : 'step'}
            aria-current={i === current ? 'step' : undefined}
          >
            <span className="step-dot" aria-hidden="true">
              {done ? '✓' : i + 1}
            </span>
            <span className="step-label">{t(step.key)}</span>
            <span className="step-time small muted">{done && at ? formatTime(at, lang) : ''}</span>
          </li>
        );
      })}
    </ol>
  );
}
