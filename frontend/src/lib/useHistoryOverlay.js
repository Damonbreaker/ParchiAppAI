import { useCallback } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';

// Open/closed state for a sheet or full-screen viewer, kept in history state so the
// Android Back button closes it instead of leaving the page.
// show() pushes an entry for the same URL; hide() goes back one step.
// -> [open, show, hide]
export function useHistoryOverlay(key) {
  const location = useLocation();
  const navigate = useNavigate();
  const open = location.state?.overlay === key;
  const show = useCallback(() => {
    if (location.state?.overlay === key) return;
    navigate(location, { state: { ...location.state, overlay: key } });
  }, [navigate, location, key]);
  const hide = useCallback(() => {
    if (location.state?.overlay !== key) return;
    // 'default' = first entry (e.g. reopened after a reload): nothing to go back to.
    if (location.key !== 'default') navigate(-1);
    else navigate(location, { replace: true, state: { ...location.state, overlay: null } });
  }, [navigate, location, key]);
  return [open, show, hide];
}
