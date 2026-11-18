# Gemini-backed OCR service for the Spring backend. Build context: ./ocr-service
FROM python:3.11-slim
WORKDIR /app
ENV PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    OCR_PIPELINE=legacy \
    PARCHI_AI_CATALOGUE=0 \
    PARCHI_CATALOGUE_DB=/app/catalogue_output/open/national_retail_catalogue_open.sqlite
COPY requirements-live.txt .
RUN pip install -r requirements-live.txt
COPY . .
EXPOSE 7870
CMD ["sh", "-c", "exec uvicorn scan_server:app --host 0.0.0.0 --port ${OCR_PORT:-7870}"]
