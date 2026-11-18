import { useCallback, useEffect, useRef, useState } from 'react';

export const PAGE_SIZE = 20;

// Drops items whose id is already in `seen` (pages shift when new items arrive).
function fresh(items, seen) {
  return items.filter((item) => !seen.has(item.id));
}

// A server list loaded 20 at a time with "Load more" (no infinite scroll).
// fetchPage({ page, size }, { signal }) -> { items, page, hasMore }.
// refresh(true) re-fetches only page 0 and merges it with the pages already loaded,
// so polling never throws away what the user scrolled to.
// key: when it changes (e.g. the inbox tab), the list starts over.
export function usePagedList(fetchPage, key) {
  const [state, setState] = useState({ status: 'loading', key });
  const refreshRef = useRef(null);
  const moreRef = useRef(null);
  const fetchRef = useRef(fetchPage);
  fetchRef.current = fetchPage;

  const refresh = useCallback(
    (silent) => {
      refreshRef.current?.abort();
      const controller = new AbortController();
      refreshRef.current = controller;
      if (!silent) {
        moreRef.current?.abort();
        setState({ status: 'loading', key });
      } else {
        setState((s) => ({ ...s, refreshing: true }));
      }
      fetchRef
        .current({ page: 0, size: PAGE_SIZE }, { signal: controller.signal })
        .then((res) => {
          const first = (res && res.items) || [];
          setState((s) => {
            if (!silent || s.status !== 'done' || s.key !== key) {
              return { status: 'done', key, pages: [first], hasMore: Boolean(res?.hasMore) };
            }
            const seen = new Set(first.map((i) => i.id));
            // Items that were on page 0 but aren't any more were pushed down by newer
            // ones; keep them at the top of page 1 so nothing vanishes from the screen.
            const pushedDown = fresh(s.pages[0], seen);
            pushedDown.forEach((i) => seen.add(i.id));
            const rest = s.pages.slice(1).map((page, index) => {
              const kept = fresh(page, seen);
              kept.forEach((i) => seen.add(i.id));
              return index === 0 ? [...pushedDown, ...kept] : kept;
            });
            const pages = [first, ...rest];
            return { ...s, pages, hasMore: rest.length ? s.hasMore : Boolean(res?.hasMore), refreshing: false, refreshError: null };
          });
        })
        .catch((error) => {
          if (controller.signal.aborted) return;
          setState((s) =>
            silent && s.status === 'done' ? { ...s, refreshing: false, refreshError: error } : { status: 'error', key, error }
          );
        });
    },
    [key]
  );

  // Pages loaded so far, read by loadMore (state updaters can't be read synchronously).
  const pageCount = useRef(0);
  pageCount.current = state.status === 'done' ? state.pages.length : 0;

  const loadMore = useCallback(() => {
    if (moreRef.current || !pageCount.current) return; // one at a time (double taps)
    const controller = new AbortController();
    moreRef.current = controller;
    setState((s) => ({ ...s, loadingMore: true, moreError: null }));
    fetchRef
      .current({ page: pageCount.current, size: PAGE_SIZE }, { signal: controller.signal })
      .then((res) => {
        setState((s) => {
          if (s.status !== 'done') return s;
          const seen = new Set(s.pages.flat().map((i) => i.id));
          return {
            ...s,
            pages: [...s.pages, fresh((res && res.items) || [], seen)],
            hasMore: Boolean(res?.hasMore),
            loadingMore: false,
          };
        });
      })
      .catch((error) => {
        if (!controller.signal.aborted) setState((s) => ({ ...s, loadingMore: false, moreError: error }));
      })
      .finally(() => {
        if (moreRef.current === controller) moreRef.current = null;
      });
  }, []);

  useEffect(() => {
    refresh(false);
    return () => {
      refreshRef.current?.abort();
      moreRef.current?.abort();
      moreRef.current = null;
    };
  }, [refresh]);

  const items = state.status === 'done' && state.key === key ? state.pages.flat() : null;
  return { state, items, refresh, loadMore };
}
