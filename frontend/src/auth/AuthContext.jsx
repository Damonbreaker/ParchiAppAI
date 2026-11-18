import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { getToken, setToken, setUnauthorizedHandler } from '../api/client.js';
import * as endpoints from '../api/endpoints.js';
import { useT } from '../i18n/useT.js';
import { captureHubReturnFromUrl } from '../lib/hubReturn.js';

const AuthContext = createContext(null);

/** Apply Hub SSO token before first paint so guards never bounce to /login. */
function takeHubToken() {
  try {
    let params = new URLSearchParams(window.location.search);
    params = captureHubReturnFromUrl(params);
    const hub = params.get('hubToken');
    if (hub) {
      setToken(hub);
      params.delete('hubToken');
      params.delete('hubRole');
    }
    const next = `${window.location.pathname}${params.toString() ? `?${params}` : ''}${window.location.hash}`;
    window.history.replaceState({}, '', next);
    return hub || null;
  } catch {
    return null;
  }
}

export function AuthProvider({ children }) {
  const { lang, setLang } = useT();
  const navigate = useNavigate();
  const [token, setTokenState] = useState(() => takeHubToken() || getToken());
  const [user, setUser] = useState(null);
  // loading: checking a saved token with /me; error: /me failed for a reason other than 401
  const [status, setStatus] = useState(() => (getToken() ? 'loading' : 'ready'));
  const [bootError, setBootError] = useState(null);

  const saveSession = useCallback((nextToken, nextUser) => {
    setToken(nextToken);
    setTokenState(nextToken);
    setUser(nextUser);
  }, []);

  const logout = useCallback(() => {
    saveSession(null, null);
    setBootError(null);
    setStatus('ready');
  }, [saveSession]);

  useEffect(() => {
    setUnauthorizedHandler(() => {
      logout();
      navigate('/login', { replace: true });
    });
    return () => setUnauthorizedHandler(null);
  }, [logout, navigate]);

  const loadMe = useCallback(
    (signal) => {
      setStatus('loading');
      endpoints
        .getMe({ signal })
        .then((me) => {
          setUser(me);
          if (me.preferredLang) setLang(me.preferredLang);
          setStatus('ready');
        })
        .catch((err) => {
          // A 401 has already logged out through the unauthorized handler.
          if (signal?.aborted || err.status === 401) return;
          setBootError(err);
          setStatus('error');
        });
    },
    [setLang]
  );

  useEffect(() => {
    if (!token) return;
    const controller = new AbortController();
    loadMe(controller.signal);
    return () => controller.abort();
  }, [loadMe, token]);

  const login = useCallback(
    async (phone, password) => {
      const res = await endpoints.login({ phone, password });
      saveSession(res.token, res.user);
      if (res.user.preferredLang) setLang(res.user.preferredLang);
      return res.user;
    },
    [saveSession, setLang]
  );

  const register = useCallback(
    async (form) => {
      const res = await endpoints.register(form);
      let nextUser = res.user;
      setToken(res.token);
      // Keep the language the user picked before registering.
      if (nextUser.preferredLang !== lang) {
        try {
          const updated = await endpoints.updateMe({ preferredLang: lang });
          if (updated && updated.id) nextUser = updated;
        } catch {
          // not critical; the local choice still applies
        }
      }
      saveSession(res.token, nextUser);
      return nextUser;
    },
    [lang, saveSession]
  );

  const changeLanguage = useCallback(
    (next) => {
      if (next === lang) return;
      setLang(next);
      if (user) {
        endpoints
          .updateMe({ preferredLang: next })
          .then((updated) => updated && updated.id && setUser(updated))
          .catch(() => {});
      }
    },
    [lang, setLang, user]
  );

  const value = useMemo(
    () => ({
      token,
      user,
      status,
      bootError,
      retry: () => loadMe(),
      login,
      register,
      logout,
      changeLanguage,
    }),
    [token, user, status, bootError, loadMe, login, register, logout, changeLanguage]
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used inside <AuthProvider>');
  return ctx;
}
