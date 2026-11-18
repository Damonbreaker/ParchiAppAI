import { getHubReturnUrl, goToHub } from '../lib/hubReturn.js';

/** Header / account control that returns to Parchi Hub service chooser. */
export default function HubBack({ variant = 'header' }) {
  const url = getHubReturnUrl();
  if (!url) return null;

  if (variant === 'block') {
    return (
      <button type="button" className="btn btn-secondary btn-block" onClick={goToHub}>
        ← Back to Parchi Hub
      </button>
    );
  }

  return (
    <button type="button" className="hub-back" onClick={goToHub} title="Back to Parchi Hub">
      ← Hub
    </button>
  );
}
