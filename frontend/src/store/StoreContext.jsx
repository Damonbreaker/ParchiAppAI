import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import { Navigate } from 'react-router-dom';
import { getMyStore } from '../api/endpoints.js';
import ErrorBox from '../components/ErrorBox.jsx';
import Loader from '../components/Loader.jsx';

const StoreContext = createContext(null);

// Loads the owner's store once for the whole /s group. store === null means
// the owner has not set one up yet (404 NO_STORE).
export function StoreProvider({ children }) {
  const [state, setState] = useState({ status: 'loading', store: null, error: null });

  const load = useCallback((signal) => {
    setState({ status: 'loading', store: null, error: null });
    getMyStore({ signal })
      .then((store) => setState({ status: 'ready', store, error: null }))
      .catch((error) => {
        if (signal?.aborted) return;
        if (error.status === 404) setState({ status: 'ready', store: null, error: null });
        else setState({ status: 'error', store: null, error });
      });
  }, []);

  useEffect(() => {
    const controller = new AbortController();
    load(controller.signal);
    return () => controller.abort();
  }, [load]);

  const setStore = useCallback((store) => setState({ status: 'ready', store, error: null }), []);

  const value = useMemo(() => ({ ...state, setStore, reload: () => load() }), [state, setStore, load]);
  return <StoreContext.Provider value={value}>{children}</StoreContext.Provider>;
}

export function useStore() {
  const ctx = useContext(StoreContext);
  if (!ctx) throw new Error('useStore must be used inside <StoreProvider>');
  return ctx;
}

function StoreGate({ needStore, children }) {
  const { status, store, error, reload } = useStore();
  if (status === 'loading') return <Loader />;
  if (status === 'error') return <ErrorBox error={error} onRetry={reload} />;
  if (needStore && !store) return <Navigate to="/s/setup" replace />;
  if (!needStore && store) return <Navigate to="/s/store" replace />;
  return children;
}

// Screens that need a store send owners without one to setup.
export const RequireStore = ({ children }) => <StoreGate needStore>{children}</StoreGate>;
// Setup (create) is only for owners without a store.
export const RequireNoStore = ({ children }) => <StoreGate needStore={false}>{children}</StoreGate>;
