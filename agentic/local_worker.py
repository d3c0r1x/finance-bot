from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Iterable

from agentic.local_llm import LocalLLMConfig, LocalLLMError, check_connection, chat, parse_json_or_summary


SYSTEM_PROMPT = """You are a local code worker inside FinPulse repo.
Work as a cheap local assistant under a stronger orchestrator.
Do not modify files.
Return compact JSON only:
{
  "summary": "short result",
  "relevant_files": [{"path": "...", "reason": "..."}],
  "findings": [{"severity": "low|medium|high", "path": "...", "problem": "...", "fix": "..."}],
  "recommended_actions": ["..."]
}
"""


MODE_PROMPTS = {
    "summarize": "Summarize files and task context. Focus on facts useful for next agent step.",
    "review": "Review for bugs, broken assumptions, missing tests, security issues, and integration risks.",
    "plan": "Create implementation plan. Keep scope lean. Reuse existing code. List stop condition.",
    "patch-hints": "Suggest exact edit locations and small patch hints. Do not output full large files.",
}


def build_prompt(mode: str, task: str, files: Iterable[Path], max_chars: int) -> str:
    chunks: list[str] = [
        f"Mode: {mode}",
        f"Task: {task}",
        MODE_PROMPTS[mode],
        "Files:",
    ]
    remaining = max_chars
    for path in files:
        label = str(path)
        try:
            text = path.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            text = path.read_text(encoding="utf-8", errors="replace")
        except OSError as exc:
            chunks.append(f"\n--- {label}\nREAD_ERROR: {exc}")
            continue
        if remaining <= 0:
            chunks.append(f"\n--- {label}\nTRUNCATED: file budget exhausted")
            continue
        excerpt = text[:remaining]
        remaining -= len(excerpt)
        chunks.append(f"\n--- {label}\n{excerpt}")
        if len(text) > len(excerpt):
            chunks.append("\nTRUNCATED: max character budget reached")
    return "\n".join(chunks)


def run_worker(args: argparse.Namespace) -> dict:
    config = LocalLLMConfig(
        base_url=args.base_url,
        model=args.model,
        api_key=args.api_key,
        timeout_seconds=args.timeout,
    )
    if args.check:
        return check_connection(config)
    prompt = build_prompt(args.mode, args.task, [Path(file) for file in args.files], args.max_chars)
    raw = chat(
        [
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": prompt},
        ],
        config=config,
        temperature=args.temperature,
        max_tokens=args.max_tokens,
    )
    result = parse_json_or_summary(raw)
    result.setdefault("mode", args.mode)
    result.setdefault("model", args.model)
    return result


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Run Bonsai/llama.cpp as local FinPulse worker.")
    parser.add_argument("--mode", choices=sorted(MODE_PROMPTS), default="summarize")
    parser.add_argument("--task", default="Inspect provided files.")
    parser.add_argument("--files", nargs="*", default=[])
    parser.add_argument("--base-url", default=LocalLLMConfig().base_url)
    parser.add_argument("--model", default=LocalLLMConfig().model)
    parser.add_argument("--api-key", default=LocalLLMConfig().api_key)
    parser.add_argument("--timeout", type=float, default=LocalLLMConfig().timeout_seconds)
    parser.add_argument("--temperature", type=float, default=0.1)
    parser.add_argument("--max-tokens", type=int, default=2048)
    parser.add_argument("--max-chars", type=int, default=60_000)
    parser.add_argument("--check", action="store_true", help="Check llama.cpp OpenAI-compatible endpoint.")
    return parser.parse_args()


def main() -> None:
    try:
        result = run_worker(parse_args())
    except LocalLLMError as exc:
        result = {
            "ok": False,
            "error": str(exc),
            "hint": "Check FINPULSE_LOCAL_LLM_BASE_URL and FINPULSE_LOCAL_LLM_API_KEY.",
        }
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
