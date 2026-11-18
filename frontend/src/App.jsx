import { Suspense } from 'react';
import { useLocation, useRoutes } from 'react-router-dom';
import routes from './routes.jsx';
import { useAuth } from './auth/AuthContext.jsx';
import BottomNav from './components/BottomNav.jsx';
import ErrorBoundary from './components/ErrorBoundary.jsx';
import ErrorBox from './components/ErrorBox.jsx';
import HubBack from './components/HubBack.jsx';
import LangToggle from './components/LangToggle.jsx';
import Loader from './components/Loader.jsx';
import OfflineBar from './components/OfflineBar.jsx';
import { useT } from './i18n/useT.js';

export default function App() {
  const { t } = useT();
  const { user, status, bootError, retry, logout } = useAuth();
  const element = useRoutes(routes);
  const { pathname } = useLocation();
  const loggedIn = status === 'ready' && !!user;

  let content;
  if (status === 'loading') {
    content = <Loader />;
  } else if (status === 'error') {
    content = (
      <div className="screen">
        <ErrorBox error={bootError} onRetry={retry} />
        <button type="button" className="btn btn-secondary btn-block" onClick={logout}>
          {t('account.logout')}
        </button>
      </div>
    );
  } else {
    content = (
      <ErrorBoundary resetKey={pathname}>
        <Suspense
          fallback={
            <div className="screen">
              <Loader lines={5} />
            </div>
          }
        >
          {element}
        </Suspense>
      </ErrorBoundary>
    );
  }

  return (
    <div className="app">
      <header className="app-header">
        <div className="app-header-start">
          <HubBack />
          <h1 className="app-title">{t('app.name')}</h1>
        </div>
        {status === 'ready' && !user && <LangToggle className="on-primary" />}
      </header>
      <OfflineBar />
      <main className={loggedIn ? 'app-main' : 'app-main no-nav'}>{content}</main>
      {loggedIn && <BottomNav role={user.role} />}
    </div>
  );
}
