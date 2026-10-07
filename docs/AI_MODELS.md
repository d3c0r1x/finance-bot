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
