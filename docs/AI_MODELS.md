# Local AI models and cloud opt-in

The Intelligence service uses a self-hosted Ollama endpoint by default. Model inventory is metadata only: the service reads the local `/api/tags` response with a short timeout, strict response bounds, and a short cache. It does not send transaction, receipt, or account data while checking installed models.

## Local setup

Set these values in the private service environment:

```dotenv
FINANCE_AI_POLICY=local-only
OLLAMA_HOST=http://ollama:11434
OLLAMA_MODEL=qwen2.5:7b-instruct
OLLAMA_MODEL_PREFERENCE=qwen3:8b,qwen2.5:7b-instruct,qwen2.5:7b,qwen3:4b,gemma3:4b,qwen2.5:3b-instruct,llama3.2:3b,qwen2.5:1.5b-instruct-q4_K_M
```

The configured model is used only when its exact tag is installed. Otherwise the service chooses the first installed exact tag from `OLLAMA_MODEL_PREFERENCE`, then another installed non-embedding model. It never silently changes a specific missing tag to a weaker tag from the same model family. Embedding models are not text-generation fallbacks. The inventory and preference lists are bounded.

Manage local models on the Ollama host with its CLI:

```sh
ollama list
ollama pull qwen2.5:7b-instruct
ollama rm qwen2.5:7b-instruct
```

After changing environment values, restart the Intelligence service. Install only models the host can run within its available memory and GPU/CPU capacity.

Vision is separate: `OLLAMA_VISION_MODELS` remains an explicit allowlist. A local allowlisted vision model must also appear in the installed inventory. No other model is selected for receipt vision.

## When Ollama is unavailable

Text generation returns the stable `503 {"error":"unavailable"}` response when no usable local model is installed or Ollama cannot provide its local inventory. The service does not send the request to another host or invent an AI result. Manual finance operations remain independent of this AI endpoint. Tesseract receipt OCR remains available without Ollama.

## Explicit remote opt-in

Remote Ollama is external processing and is disabled by default. It requires both service configuration and request policy to be `cloud-opt-in`. Configure the remote host, exact model, and API key only in the deployment's private secret/configuration store:

```dotenv
FINANCE_AI_POLICY=cloud-opt-in
OLLAMA_HOST=https://ollama.com
OLLAMA_MODEL=gpt-oss:20b
OLLAMA_API_KEY=<secret-store-reference>
```

The service never queries `/api/tags` on a remote host and never routes local-only requests to it. Cloud mode uses only the configured model; an outage does not trigger another provider. Tests do not make billable cloud requests.

The private health panel reports remote model status as unchecked. It does not enumerate a remote model inventory to make that status look more precise.

There is intentionally no tenant-facing model selector or model download/delete endpoint. Model lifecycle belongs to the deployment operator.

## Gemini Developer API

Gemini is an optional server-side provider for structured text tasks and, when separately approved, minimized receipt images. Android and Telegram never receive the Gemini key. Gemini stays disabled unless all of these are true:

- `FINANCE_AI_POLICY=cloud-opt-in` on the service and the individual request asks for `cloud-opt-in`;
- `GEMINI_LIVE_TRAFFIC_APPROVED=true`;
- `GEMINI_API_KEY` is supplied by the deployment secret store;
- `GEMINI_MODEL_ROUTES` contains an explicit, bounded list of model IDs and task capabilities.

Do not set the live-traffic flag until the exact Google account/model availability, billing status, current terms, data-processing conditions, and Russian cross-border data requirements have been checked. This code does not bypass provider region restrictions. Unit tests use an injected mock HTTP transport and never call Gemini.

Receipt image processing has an extra `GEMINI_RECEIPT_IMAGES_APPROVED=true` gate. The service decodes the submitted image, removes metadata, corrects orientation, scales it to at most 2200 pixels on the long side, and re-encodes it as a bounded JPEG before upload. This is still receipt content; the image gate must reflect explicit user consent and the operator's data policy. Gemini does not receive full transaction history: budget proposals accept only validated monthly aggregates, transaction drafts accept one bounded user statement, basket review accepts item names without prices, and merchant classification accepts merchant names.

Configure exact model IDs and limits in private deployment configuration. Example shape (replace every placeholder with a verified value and limits below the account's current quota):

```dotenv
FINANCE_AI_POLICY=cloud-opt-in
GEMINI_LIVE_TRAFFIC_APPROVED=false
GEMINI_RECEIPT_IMAGES_APPROVED=false
GEMINI_API_KEY=<secret-store-injected-value>
GEMINI_MODEL_ROUTES=[{"modelId":"<verified-model-id>","tasks":["merchant-classification","receipt-basket-review"],"capabilities":["text","structured_output"],"complexity":"fast","rpm":5,"tpm":250000,"rpd":20}]
```

`GEMINI_MODEL_ROUTES` is an ordered JSON array. Each entry has exactly `modelId`, `tasks`, `capabilities`, `complexity`, `rpm`, `tpm`, and `rpd`. Supported task classes are `fast`, `balanced`, `reasoning`, and `vision`. Task class defaults are server-owned: merchant classification and basket review are fast, transaction draft is balanced, budget proposal is reasoning, and receipt reading is vision. The router prefers a configured class match and can use only models whose task and capabilities are explicitly listed. It tries the preferred model and at most one compatible alternate on a quota response, 5xx, timeout, or invalid structured output. It never fans one request out to every model.

The user-provided Gemini console list is a quota planning hint only. Model names and availability change; do not copy display labels into configuration without checking the current API model ID. The configured RPM, TPM, and RPD values are conservative per-process guards, not a provider quota guarantee. They reset on service restart and are not shared across replicas; keep the friends MVP to one Intelligence replica or add shared coordination before scaling horizontally. Requests that exceed a configured model limit move to the one allowed compatible fallback. If no route succeeds, the API returns only `503 {"error":"unavailable"}`; clients should show “Временно недоступно” and preserve manual finance workflows.

The adapter sends only the validated task prompt and optional sanitized receipt image. It does not store prompts, image contents, raw vendor errors, or API keys in telemetry. Responses are checked against the task's existing validator; transaction drafts remain review-only and never mutate financial records. Provider/model, safe usage counts when supplied, status, and latency are returned as provenance only.
