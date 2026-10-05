# Receipt photo processing v1 (F10)

This spec adds private image upload, durable OCR processing, progress and a
reviewable receipt draft. OCR output is evidence, not a financial mutation.
Posting a transaction still requires the existing explicit receipt-confirm
action. F11 and F13 later add Vision and structured two-reader reconciliation;
F10 does not invent totals or item rows when OCR has not extracted them.

## Upload and storage

- Core and BFF expose `POST /tenants/{tenantId}/receipts/photo-jobs` as one
  `multipart/form-data` image part plus a required `Idempotency-Key` header.
  BFF calls require an authenticated session and CSRF token.
- Accept JPEG and PNG only, at most 10 MiB and 25 megapixels. Verify the byte
  signature and decode the image before storage. Reject malformed images,
  unsupported formats, oversized pixel dimensions and empty files with `400`.
- Generate document, job and object IDs server-side. Never use the uploaded
  filename as a path or key. Persist SHA-256, detected MIME, byte count, safe
  display filename and `scan_state=pending` in the existing `documents` table.
- Store original bytes in private quarantine. SeaweedFS is the local S3-compatible
  service; the Core adapter also supports a configured S3 endpoint. A test/local
  filesystem adapter uses canonicalized paths and generated keys. No public URL
  is returned. S3-compatible buckets must deny public access at the provider
  policy level; object metadata is not treated as an access-control mechanism.
- Run malware scanning before OCR. A positive scan rejects the job and keeps the
  document unavailable. A scanner outage is retryable; no OCR request is made
  while the document remains quarantined.

## Durable job and retry behavior

- Each accepted document has one persistent `receipt_processing_jobs` row with
  tenant, owner, document, idempotency key, request hash, state, stage, percent,
  attempt count, lease, retry time, safe error code and optional receipt ID.
- States are `queued`, `running`, `retryable`, `completed` and `rejected`.
  Progress is monotonic: queued, scanning, optional Vision, OCR, draft creation,
  complete.
- A repeated upload with the same owner, idempotency key and image hash returns
  the existing job. Reusing that key for different bytes returns `409`. A
  repeated start for an already accepted document returns the same job; it never
  creates another job or receipt draft.
- Workers claim jobs with a short database lease and `FOR UPDATE SKIP LOCKED`.
  Expired leases return to the queue. Retryable failures use bounded backoff and
  a finite attempt count; invalid images and positive malware detections are
  terminal. Logs contain job/document IDs and error codes, never image bytes,
  OCR text, credentials or user filenames.
- `GET /tenants/{tenantId}/receipt-jobs/{jobId}` returns only the active
  member's job. It reports state, stage, progress, retryability, safe error code,
  timestamps and `receiptId` after draft creation.

## OCR and draft

- After a clean scan, the worker calls the private Python OCR route under the
  local-only AI policy, validates the versioned response, and persists the OCR
  reading/provenance in `receipt_readings`.
- The worker creates one owner-scoped receipt in `review_required`, linked to the
  ready document, with `selectedReader=ocr`. F10 saves recognized text and word
  evidence; unknown cash total, merchant, date and items stay unknown. The user
  may correct them in the receipt review UI. The OCR result never creates a
  transaction by itself.
- Draft creation uses a stable job-derived idempotency key. If a worker crashes
  after creating the receipt but before completing the job, retry finds the same
  receipt and finishes the same job.
- The Web page uploads the image, shows job progress and links to the draft when
  ready. It polls the owner-scoped status endpoint with bounded backoff and stops
  polling when the component unmounts or the job reaches a terminal state.

## F11 Vision and OCR handoff

- Vision uses only explicitly configured models from the ordered
  `OLLAMA_VISION_MODELS` allowlist. The pool is limited to three unique model
  tags and tries them in order. The response records the actual model and
  prompt versions; invalid or unavailable models move to the next configured
  model. A process-wide single slot bounds concurrent Ollama model residency.
- Image preparation applies EXIF orientation, strips metadata by PNG
  re-encoding, and limits the longest edge to 3000 pixels. Vision accepts only
  the exact structured schema and makes one fixed-prompt repair attempt. It
  never fills absent values by calculation or inference.
- The worker keeps Vision and OCR as separate typed readers. A Vision outage,
  timeout, invalid result or exhausted model pool is recorded as a safe
  `visionFallbackReason`, then the local-only OCR task runs. Its distinct
  `ocrFallbackReason` is retained when Vision succeeds but OCR does not. The
  selected reader is OCR when available and Vision otherwise. Either path
  creates only a review draft; neither posts a transaction.
- `local-only` is the default. A remote Ollama host needs the explicit
  `cloud-opt-in` policy, and fallback never changes that policy or selects an
  unlisted model. The UI displays actual reader versions and available fallback
  reasons.

## Acceptance

- PostgreSQL tests prove tenant/member isolation, one job for retries, stale or
  conflicting idempotency, lease recovery, monotonic progress, one draft after a
  worker crash/replay, scanner fail-closed behavior and no transaction before
  explicit confirmation.
- Storage tests prove private generated keys, byte-for-byte retrieval, deletion,
  and path traversal rejection. The `receipt-storage.yml` CI profile runs
  authenticated SeaweedFS S3 round-trip/anonymous-read checks and ClamAV EICAR
  detection; deterministic unit tests use isolated adapters.
- Web tests cover upload, progress, failure, retry and open-draft states in RU/EN.
- F11 tests cover ordered model fallback, resource-slot bounds and cancellation,
  strict Vision provenance, typed Vision-to-OCR fallback, Vision-only draft
  completion, and the absence of an automatic transaction. Live model quality
  requires a separately configured local model and golden receipt set.
