# Kirana frontend — rules for Claude

Read PLAN.md before any task. Users are on low-end Android phones with slow data.

- Vite + React + React Router only. Do NOT add UI libraries, axios, Redux, moment, lodash or icon packs without asking.
- Plain CSS with variables in styles/base.css. Mobile-first at 360 px; tap targets >= 44 px; system fonts.
- Every UI string goes through useT() with keys in i18n/en.json and i18n/hi.json. No hardcoded text.
- API calls only through src/api/client.js. Backend runs at http://localhost:8080/api.
- Lazy-load customer and store route groups.
- Every screen has loading, empty and error states.
- Compress images with lib/compressImage.js before upload.
- Build only the current phase. After each task, report the gzipped bundle size from `vite build` (budget: 150 KB JS).
