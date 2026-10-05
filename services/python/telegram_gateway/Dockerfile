FROM python:3.12.14-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

WORKDIR /app
COPY services/python/telegram_gateway/requirements.txt ./requirements.txt
RUN pip install --no-cache-dir --requirement requirements.txt

COPY services/python/__init__.py ./services/python/__init__.py
COPY services/python/telegram_gateway ./services/python/telegram_gateway

USER 10001:10001
EXPOSE 8443
ENTRYPOINT ["python", "-m", "services.python.telegram_gateway.app"]
