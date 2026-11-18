import { Component } from 'react';
import { useT } from '../i18n/useT.js';

// A lazy screen that failed to download (slow data, or an old tab after a new
// build) is a dynamic-import error; only a full reload fixes that.
const isChunkError = (error) =>
  /dynamically imported module|Importing a module script failed|Failed to fetch|ChunkLoadError|Loading chunk/i.test(
    String(error?.message || error)
  );

function Fallback({ chunk, onRetry }) {
  const { t } = useT();
  return (
    <div className="screen">
      <div className="card error-box" role="alert">
        <p className="error-title">{t(chunk ? 'crash.chunkTitle' : 'crash.title')}</p>
        <p className="muted">{t(chunk ? 'crash.chunkText' : 'crash.text')}</p>
        <button type="button" className="btn" onClick={onRetry}>
          {t('common.retry')}
        </button>
      </div>
    </div>
  );
}

// Never a blank white screen: shows Retry instead. resetKey (the URL) clears the
// error when the user navigates elsewhere with the bottom bar.
export default class ErrorBoundary extends Component {
  constructor(props) {
    super(props);
    this.state = { error: null };
  }

  static getDerivedStateFromError(error) {
    return { error };
  }

  componentDidUpdate(prev) {
    if (this.state.error && prev.resetKey !== this.props.resetKey) this.setState({ error: null });
  }

  retry = () => {
    if (isChunkError(this.state.error)) window.location.reload();
    else this.setState({ error: null });
  };

  render() {
    if (this.state.error) return <Fallback chunk={isChunkError(this.state.error)} onRetry={this.retry} />;
    return this.props.children;
  }
}
