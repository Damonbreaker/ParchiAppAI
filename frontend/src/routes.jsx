import { lazy } from 'react';
import { Navigate } from 'react-router-dom';
import Login from './auth/Login.jsx';
import Register from './auth/Register.jsx';
import { RequireGuest, RequireRole, RootRedirect } from './auth/guards.jsx';

// Customer and store screens are separate lazy chunks.
const CustomerApp = lazy(() => import('./customer/index.jsx'));
const StoreApp = lazy(() => import('./store/index.jsx'));
const SharedListPage = lazy(() => import('./customer/SharedListPage.jsx'));

const routes = [
  { path: '/', element: <RootRedirect /> },
  {
    path: '/login',
    element: (
      <RequireGuest>
        <Login />
      </RequireGuest>
    ),
  },
  {
    path: '/register',
    element: (
      <RequireGuest>
        <Register />
      </RequireGuest>
    ),
  },
  // Public group-list join (WhatsApp friends — no login required to add items).
  { path: '/join/:token', element: <SharedListPage /> },
  {
    path: '/c/*',
    element: (
      <RequireRole role="CUSTOMER">
        <CustomerApp />
      </RequireRole>
    ),
  },
  {
    path: '/s/*',
    element: (
      <RequireRole role="STORE_OWNER">
        <StoreApp />
      </RequireRole>
    ),
  },
  { path: '*', element: <Navigate to="/" replace /> },
];

export default routes;
