"""Run the synthetic AI provider gate configured in the current environment."""

from __future__ import annotations

import argparse
import json
import os
import sys

from services.python.intelligence.app import IntelligenceHandler
from services.python.intelligence.evaluation import evaluate_providers, load_golden_dataset


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Evaluate configured Finance Bot AI providers on synthetic cases")
    parser.add_argument("--policy", choices=("local-only", "cloud-opt-in"),
                        default=os.getenv("FINANCE_AI_POLICY", "local-only"))
    parser.add_argument("--minimum-pass-rate", type=float, default=1.0)
    parser.add_argument("--timeout-seconds", type=float, default=180.0)
    args = parser.parse_args(argv)

    dataset = load_golden_dataset()
    handler = object.__new__(IntelligenceHandler)
    providers = handler._providers()
    report = evaluate_providers(
        providers,
        dataset.cases,
        execution_policy=args.policy,
        minimum_pass_rate=args.minimum_pass_rate,
        timeout_seconds=args.timeout_seconds,
        provider_api_keys={id(provider): os.getenv("OLLAMA_API_KEY", "")
                           for provider in providers if provider.name == "ollama"},
    )
    report["datasetVersion"] = dataset.version
    tasks = sorted({case.task_kind for case in dataset.cases})
    report["taskCoverage"] = {
        task: any(
            task in configured.supported_tasks
            and evaluated["eligible"]
            for configured, evaluated in zip(providers, report["providers"])
        )
        for task in tasks
    }
    print(json.dumps(report, ensure_ascii=False, sort_keys=True, indent=2))
    return 0 if all(report["taskCoverage"].values()) else 2


if __name__ == "__main__":
    raise SystemExit(main())
