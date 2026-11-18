import { useAuth } from './AuthContext.jsx';
import HubBack from '../components/HubBack.jsx';
import LangToggle from '../components/LangToggle.jsx';
import { useT } from '../i18n/useT.js';
import { getHubReturnUrl } from '../lib/hubReturn.js';

// Shared by the customer and store route groups.
export default function Account() {
  const { t } = useT();
  const { user, logout } = useAuth();
  const hub = getHubReturnUrl();

  return (
    <section className="screen">
      <h2>{t('nav.account')}</h2>
      {hub && (
        <p className="muted small" style={{ marginBottom: '0.75rem' }}>
          Switch groceries, salon, auto or laundry from Parchi Hub without reloading the Hub link.
        </p>
      )}
      {hub && <HubBack variant="block" />}
      <dl className="card info-list">
        <div>
          <dt>{t('account.name')}</dt>
          <dd>{user.name}</dd>
        </div>
        <div>
          <dt>{t('account.phone')}</dt>
          <dd>{user.phone}</dd>
        </div>
        <div>
          <dt>{t('account.role')}</dt>
          <dd>{t(`role.${user.role}`)}</dd>
        </div>
      </dl>
      <div className="card setting-row">
        <span className="label">{t('account.language')}</span>
        <LangToggle />
      </div>
      <button type="button" className="btn btn-danger btn-block" onClick={logout}>
        {t('account.logout')}
      </button>
    </section>
  );
}
