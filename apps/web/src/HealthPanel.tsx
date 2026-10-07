import { useQuery } from '@tanstack/react-query';
import { api, type HealthCapability } from './api';

const text = {
  ru: {
    title: 'Состояние сервисов', description: 'Доступность функций распознавания и локального ИИ.',
    localAi: 'Локальный ИИ', vision: 'Распознавание чеков по фото', ocr: 'OCR чеков',
    available: 'Работает', unavailable: 'Недоступен', disabled: 'Выключен',
    OLLAMA_UNAVAILABLE: 'Сервис модели не отвечает.', MODEL_MISSING: 'Настроенная модель не установлена.',
    REMOTE_MODEL_STATUS_UNCHECKED: 'Список удалённых моделей не запрашивается.',
    MODEL_CONFIG_INVALID: 'Проверьте настройки локальной модели.',
    VISION_DISABLED: 'Распознавание фото выключено.', VISION_CONFIG_INVALID: 'Настройки распознавания фото требуют проверки.',
    TESSERACT_MISSING: 'OCR-компонент не установлен.', HEALTH_CHECK_FAILED: 'Проверка зависимости не завершилась.',
    HEALTH_SERVICE_UNAVAILABLE: 'Сервис состояния временно недоступен.',
    error: 'Не удалось загрузить состояние сервисов', retry: 'Повторить', refresh: 'Обновить статус', loading: 'Проверяем сервисы…',
  },
  en: {
    title: 'Service status', description: 'Availability of receipt recognition and local AI features.',
    localAi: 'Local AI', vision: 'Receipt photo recognition', ocr: 'Receipt OCR',
    available: 'Available', unavailable: 'Unavailable', disabled: 'Disabled',
    OLLAMA_UNAVAILABLE: 'The model service is not responding.', MODEL_MISSING: 'The configured model is not installed.',
    REMOTE_MODEL_STATUS_UNCHECKED: 'Remote model inventory is not queried.',
    MODEL_CONFIG_INVALID: 'Check the local model configuration.',
    VISION_DISABLED: 'Photo recognition is disabled.', VISION_CONFIG_INVALID: 'Photo recognition settings need review.',
    TESSERACT_MISSING: 'The OCR component is not installed.', HEALTH_CHECK_FAILED: 'A dependency check did not finish.',
    HEALTH_SERVICE_UNAVAILABLE: 'The status service is temporarily unavailable.',
    error: 'Could not load service status', retry: 'Retry', refresh: 'Check again', loading: 'Checking services…',
  },
} as const;

export function HealthPanel({ tenantId, language }: { tenantId: string; language: 'ru' | 'en' }) {
  const t = text[language];
  const health = useQuery({ queryKey: ['health', tenantId], queryFn: () => api.getHealth(tenantId), retry: false, staleTime: 15_000 });
  const rows: Array<[keyof typeof t, HealthCapability | undefined]> = [
    ['localAi', health.data?.capabilities.localAi],
    ['vision', health.data?.capabilities.receiptVision],
    ['ocr', health.data?.capabilities.receiptOcr],
  ];

  return <section className="panel health-panel" aria-labelledby="health-title">
    <div className="panel-heading">
      <div><span className="eyebrow">{t.description}</span><h2 id="health-title">{t.title}</h2></div>
      <button className="button button-quiet" disabled={health.isFetching} onClick={() => void health.refetch()}>
        {health.isFetching ? t.loading : t.refresh}
      </button>
    </div>
    {health.isPending && <p role="status">{t.loading}</p>}
    {health.isError && <div role="alert"><p>{t.error}</p>
      <button className="button button-quiet" onClick={() => void health.refetch()}>{t.retry}</button>
    </div>}
    {health.data && <div className="health-list">
      {rows.map(([key, capability]) => capability && <article className="health-row" key={key}>
        <div><strong>{t[key]}</strong>
          {capability.diagnosticCode && <p>{t[capability.diagnosticCode as keyof typeof t] ?? t.unavailable}</p>}
        </div>
        <span className={`health-status health-status-${capability.status}`}>{t[capability.status]}</span>
      </article>)}
    </div>}
  </section>;
}
