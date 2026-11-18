import { errorText } from './ErrorBox.jsx';
import { useT } from '../i18n/useT.js';

// "Load more" under a paged list. Shows its own spinner and error.
export default function LoadMore({ state, onLoadMore }) {
  const { t } = useT();
  if (state.status !== 'done' || !state.hasMore) return null;
  return (
    <div className="load-more">
      {state.moreError && (
        <p className="field-error" role="alert">
          {errorText(state.moreError, t)}
        </p>
      )}
      <button
        type="button"
        className="btn btn-secondary btn-block"
        onClick={onLoadMore}
        disabled={state.loadingMore}
        aria-busy={state.loadingMore}
      >
        {state.loadingMore && <span className="spinner" aria-hidden="true" />}
        {state.loadingMore ? t('common.loading') : state.moreError ? t('common.retry') : t('list.loadMore')}
      </button>
    </div>
  );
}
