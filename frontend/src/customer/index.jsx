import { Suspense, lazy } from 'react';
import { Navigate, Route, Routes } from 'react-router-dom';
import Home from './Home.jsx';
import History from './History.jsx';
import LocationSetup from './LocationSetup.jsx';
import NewListStart from './NewListStart.jsx';
import NewRequest from './NewRequest.jsx';
import RequestDetail from './RequestDetail.jsx';
import ReviewItems from './ReviewItems.jsx';
import SharedListPage from './SharedListPage.jsx';
import CommunityPage from './CommunityPage.jsx';
import PoolPage from './PoolPage.jsx';
import StoreSearch from './StoreSearch.jsx';
import StorePage from './StorePage.jsx';
import Account from '../auth/Account.jsx';
import Loader from '../components/Loader.jsx';

// The scan screen carries the image compression code; load it only when a photo is picked.
const ScanParchi = lazy(() => import('./ScanParchi.jsx'));

// Customer route group, mounted at /c/* as its own lazy chunk.
export default function CustomerApp() {
  return (
    <Routes>
      <Route index element={<Home />} />
      <Route path="location" element={<LocationSetup />} />
      <Route path="search" element={<StoreSearch />} />
      <Route path="stores/:id" element={<StorePage />} />
      <Route path="new" element={<NewListStart />} />
      <Route
        path="new/scan"
        element={
          <Suspense
            fallback={
              <section className="screen">
                <Loader lines={4} />
              </section>
            }
          >
            <ScanParchi />
          </Suspense>
        }
      />
      <Route path="new/edit" element={<NewRequest />} />
      <Route path="new/review" element={<ReviewItems />} />
      <Route path="shared/:token" element={<SharedListPage />} />
      <Route path="communities" element={<CommunityPage />} />
      <Route path="pool/:token" element={<PoolPage />} />
      <Route path="requests/:id" element={<RequestDetail />} />
      <Route path="orders" element={<History />} />
      <Route path="account" element={<Account />} />
      <Route path="*" element={<Navigate to="/c" replace />} />
    </Routes>
  );
}
