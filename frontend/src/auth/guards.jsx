import { Navigate, useLocation } from 'react-router-dom';
import { useAuth } from './AuthContext.jsx';

// URL prefix each role's screens live under.
const BASE = { CUSTOMER: '/c', STORE_OWNER: '/s' };

export function homeFor(role) {
  return BASE[role] || '/c';
}

export function RootRedirect() {
  const { user, status } = useAuth();
  if (status === 'loading') return null;
  return <Navigate to={user ? homeFor(user.role) : '/login'} replace />;
}

// Login and register: a logged-in user goes to the page they came for, or home.
export function RequireGuest({ children }) {
  const { user, status } = useAuth();
  const location = useLocation();
  if (status === 'loading') return null;
  if (!user) return children;
  const from = location.state?.from;
  const target = from && from.startsWith(BASE[user.role]) ? from : homeFor(user.role);
  return <Navigate to={target} replace />;
}

// Runs before the lazy route group loads, so the wrong role never downloads it.
export function RequireRole({ role, children }) {
  const { user, status } = useAuth();
  const location = useLocation();
  if (status === 'loading') return null;
  if (!user) return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  if (user.role !== role) return <Navigate to={homeFor(user.role)} replace />;
  return children;
}
