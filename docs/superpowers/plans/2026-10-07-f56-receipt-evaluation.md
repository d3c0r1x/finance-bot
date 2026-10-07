# F56 receipt evaluation CLI and CI privacy

**Global source:** `PLAN.md` F56. The user-approved V1 plan keeps F01–F60 intact and requires tests before implementation.

## Acceptance

- Provide a new module command at `python -m tools.evaluation.receipt_inventory`.
- Keep `python receipt_inventory.py` as a compatible root entry point.
- Preserve `--vision`, `--strict`, `--json`, `--limit`, `--debug`, and positional photo arguments.
- Keep photo collection read-only and deterministic: explicit paths, sorted `data/receipts`, then local `receipt_samples.json`; deduplicate without reordering.
- `--json` must emit valid JSON only on stdout, including an empty inventory; diagnostics belong on stderr.
- Strict mode returns 1 for existing hard defects and 0 for a clean inventory or no photos.
- Synthetic fixture tests run in CI without Ollama or private photos. Local private sample paths remain supported but do not enter tracked files, CI logs, or uploaded artifacts.
- CI checks tracked private receipt paths and runs an empty-inventory JSON smoke. Workflow must not upload receipt outputs as artifacts.

## Existing behavior and refactor boundary

The root `receipt_inventory.py` is the current implementation. It reads explicit paths, ignored `data/receipts`, and `receipt_samples.json`; calls Tesseract or Vision; never writes database rows or photo bytes. `docs/EVALUATION.md` and F56 map the command into `tools/evaluation`. The workflow currently has no artifact upload and never reads owner files from a developer checkout.

Move the implementation into `tools/evaluation/receipt_inventory.py` and leave a thin root wrapper. Keep collection order, scoring, per-photo error isolation, human output, and strict hard-defect policy unchanged. The JSON contract and empty JSON result are explicitly in scope because `--json` currently appends human summary text and emits a human notice for an empty inventory, so output is not parseable JSON.

## Test-first sequence

1. Add focused tests for flag parsing, root compatibility, collection ordering/deduplication, sample path handling with temporary synthetic files, OCR/Vision selection, per-photo failures, strict exit codes, and valid JSON-only stdout including empty inventory.
2. Run tests and record observed RED before moving or changing implementation.
3. Extract implementation to the package module; keep wrapper behavior and root invocation.
4. Add CI guards proving no tracked `receipt_samples.json` or `data/receipts` files, run the new module in empty JSON mode, and keep synthetic tests free of private corpus inputs. Do not add artifact upload.
5. Run focused tests, full receipt-related Python regressions, CI workflow syntax/contract checks, and `git diff --check`. Review paths and staged files for private data.
6. Commit F56 after GREEN; record SHA in `.agent/PROGRESS.md` immediately after commit.

## Verification limits

- CI validates synthetic control-flow and the absence of committed private files; it does not claim OCR quality on owner receipts.
- Private sample behavior is tested only with temporary synthetic files. Do not open or copy actual owner sample photos or `receipt_samples.json` during this goal.
- No CI artifact is required for this CLI; stdout is tested locally and in the workflow smoke only.
