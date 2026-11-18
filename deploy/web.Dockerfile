# Frontend build + gateway (serves SPA, proxies /api to the backend). Build context: repo root.
FROM node:22-slim AS frontend
WORKDIR /fe
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
# VITE_DEMO=1 enables the demo location/tower features for public visitors.
ARG VITE_DEMO=1
ENV VITE_DEMO=${VITE_DEMO}
RUN npm run build

FROM python:3.11-slim
WORKDIR /app
ENV PYTHONUNBUFFERED=1 PIP_NO_CACHE_DIR=1 PARCHI_UI_PORT=8080 PARCHI_API=http://backend:8081
RUN pip install fastapi uvicorn httpx
COPY scripts/gateway.py scripts/gateway.py
COPY --from=frontend /fe/dist frontend/dist
EXPOSE 8080
CMD ["python", "scripts/gateway.py"]
