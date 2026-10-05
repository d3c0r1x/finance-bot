FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 FINANCE_AI_BIND=0.0.0.0 FINANCE_AI_PORT=8091
WORKDIR /app
RUN useradd --system --uid 10001 --create-home appuser
RUN apt-get update \
    && apt-get install -y --no-install-recommends tesseract-ocr tesseract-ocr-rus \
    && rm -rf /var/lib/apt/lists/*
COPY services/python/intelligence/requirements.txt ./requirements.txt
RUN pip install --no-cache-dir -r requirements.txt
COPY services/__init__.py ./services/__init__.py
COPY services/python/intelligence ./services/python/intelligence
COPY services/python/document_import ./services/python/document_import
USER 10001:10001
EXPOSE 8091
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8091/healthz', timeout=2)"
CMD ["python", "-m", "services.python.intelligence.app"]
