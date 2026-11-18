import { NavLink } from 'react-router-dom';
import { useT } from '../i18n/useT.js';

// Inline SVG paths instead of an icon pack.
const ICONS = {
  home: 'M3 11l9-8 9 8v10a1 1 0 0 1-1 1h-5v-7H9v7H4a1 1 0 0 1-1-1z',
  history: 'M6 2h12v20l-3-2-3 2-3-2-3 2zM9 7h6M9 11h6M9 15h4',
  account: 'M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8zM4 21a8 8 0 0 1 16 0',
  requests: 'M4 4h16v16H4zM4 13h5l1 3h4l1-3h5',
  store: 'M3 9l2-5h14l2 5zM4 9v11h16V9M9 20v-6h6v6',
};

const ITEMS = {
  CUSTOMER: [
    { to: '/c', key: 'nav.home', icon: 'home', end: true },
    { to: '/c/orders', key: 'nav.history', icon: 'history' },
    { to: '/c/account', key: 'nav.account', icon: 'account' },
  ],
  STORE_OWNER: [
    { to: '/s', key: 'nav.requests', icon: 'requests', end: true },
    { to: '/s/store', key: 'nav.store', icon: 'store' },
    { to: '/s/account', key: 'nav.account', icon: 'account' },
  ],
};

export default function BottomNav({ role }) {
  const { t } = useT();
  const items = ITEMS[role] || [];
  return (
    <nav className="bottom-nav">
      {items.map((item) => (
        <NavLink key={item.to} to={item.to} end={item.end} className="bottom-nav-item">
          <svg viewBox="0 0 24 24" width="24" height="24" aria-hidden="true">
            <path d={ICONS[item.icon]} />
          </svg>
          <span>{t(item.key)}</span>
        </NavLink>
      ))}
    </nav>
  );
}
