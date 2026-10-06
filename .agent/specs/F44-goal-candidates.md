# F44 — Goal candidates

Approved global requirement: `PLAN.md` F44. This spec fixes the F44.1 candidate-calculation slice; it does not expand F44 into F45 lifecycle/outcome delivery.

## Product behavior

- Suggest a goal only for a confirmed harmful/unnecessary product decision, never a model-only guess. Respect the member's allowed/confirmed overrides.
- Use confirmed receipt purchases, not recommendation/verdict counts, to calculate cadence and spend.
- A product needs at least two purchases per 30-day equivalent. Candidate count target is approximately half the usual cadence, at least one, and strictly below the rounded current rate.
- Product evidence must contain at least two harmful/unnecessary decisions. The monthly cadence is `purchase count / max(30, days between first and last purchase) * 30`, rounded to two decimals; typical purchase amount is the average of the latest six confirmed purchases.
- A sum target is half the estimated monthly spend, rounded to 10 RUB while half-spend is below 1,000 RUB and otherwise to 50 RUB; it cannot be below one typical purchase. It must leave at least 100 RUB of meaningful room; otherwise it is unavailable and the candidate is reported as skipped only in sum mode.
- Group candidates require the same confirmed evidence and at least two group purchases per 30-day equivalent. They support count goals only; never invent an allocation of group spend across products.
- Sort candidates by estimated reduction, then recurrence count; return at most three product and two group candidates, matching legacy display limits. Never include family-member aggregates or unconfirmed guesses.
- Count/sum preference is per member. Changing it affects future candidate presentation only; accepted goal target/unit is immutable (enforced in the Core F44.2 slice).
- Core acceptance will set an accepted goal's window to 30 days from acceptance and enforce one active goal per member.

## F44.1 acceptance: deterministic Go candidate calculator

- Versioned, bounded request and response; strict internal route authentication and strict JSON handling.
- Deterministic product and category candidates matching the legacy boundaries in `services/goals.py`.
- Exact edge coverage for below-threshold cadence, allowed products, guesses, confirmed evidence, count and sum minimums, category count-only behavior, sorting/limit, malformed bounds and decimal input.
- Golden fixture plus focused handler tests; `go test ./...` and `go vet ./...` pass.

## Follow-on slices

- F44.2: Core member-scoped preference and accepted-goal persistence, 30-day start, single active goal constraint, immutable accepted target/unit, API contract and PostgreSQL integration.
- F44.3: RU/EN Web goal proposal and active-goal screen using Core/Go contracts; member-only actions and no speculative financial placeholders.
- F44 is complete only when F44.1–F44.3 and their regression gates pass. F45 progress/history and F46 outcome notification remain separate goals.
