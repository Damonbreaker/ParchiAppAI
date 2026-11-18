import { Navigate, Route, Routes } from 'react-router-dom';
import { RequireNoStore, RequireStore, StoreProvider, useStore } from './StoreContext.jsx';
import Inbox from './Inbox.jsx';
import OrderWatcher from './OrderWatcher.jsx';
import RequestView from './RequestView.jsx';
import StoreHome from './StoreHome.jsx';
import StoreSetup from './StoreSetup.jsx';
import Account from '../auth/Account.jsx';

// New-order toasts on every store screen, once the owner has a store.
function WatchOrders() {
  const { store } = useStore();
  return store ? <OrderWatcher /> : null;
}

// Store owner route group, mounted at /s/* as its own lazy chunk.
export default function StoreApp() {
  return (
    <StoreProvider>
      <WatchOrders />
      <Routes>
        <Route
          index
          element={
            <RequireStore>
              <Inbox />
            </RequireStore>
          }
        />
        <Route
          path="requests/:id"
          element={
            <RequireStore>
              <RequestView />
            </RequireStore>
          }
        />
        <Route
          path="store"
          element={
            <RequireStore>
              <StoreHome />
            </RequireStore>
          }
        />
        <Route
          path="store/edit"
          element={
            <RequireStore>
              <StoreSetup mode="edit" />
            </RequireStore>
          }
        />
        <Route
          path="setup"
          element={
            <RequireNoStore>
              <StoreSetup mode="create" />
            </RequireNoStore>
          }
        />
        <Route path="account" element={<Account />} />
        <Route path="*" element={<Navigate to="/s" replace />} />
      </Routes>
    </StoreProvider>
  );
}
