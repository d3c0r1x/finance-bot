# ADR-015: Bounded Vision model pool and typed OCR fallback

**Status:** Accepted for implementation after the user's design choice
**Date:** 2026-10-04

## Context

Vision and OCR return different evidence. A single combined endpoint would make
their schemas, provenance and failure behavior harder to validate separately.
Vision models can also exhaust local GPU memory or be unavailable, while the
existing local OCR path remains useful.

## Decision

- Keep receipt Vision and OCR as separate typed tasks and persist each reading
  with its own reader, model and prompt provenance.
- Read Vision model tags from the explicit, ordered `OLLAMA_VISION_MODELS`
  allowlist. Enforce a maximum of three unique tags, fail closed on malformed
  configuration, and record the model that actually returned the result.
- Serialize Ollama inference through one process-wide slot. A model timeout,
  unavailable model or invalid result advances only to the next configured
  Vision model. It does not silently change the endpoint, execution policy or
  provider.
- When all Vision models fail, retain a safe `visionFallbackReason` and run the
  local-only OCR task. When Vision succeeds and OCR fails, complete a
  Vision-selected review draft and retain `ocrFallbackReason`. Both paths keep
  the draft unposted until explicit user confirmation.
- Keep `local-only` as the default. Access to a remote Ollama endpoint requires
  explicit `cloud-opt-in`; the same policy applies to all configured models.

## Consequences

The API and Web client expose both readers and their provenance without
merging their confidence or implying that either is authoritative. Deterministic
tests cover the pool, scheduler and handoff. Live model quality remains an
environment-specific evaluation because it needs configured weights and a
golden receipt set.
