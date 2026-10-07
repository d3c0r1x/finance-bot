# F53 Web panel parity — execution plan

**Status: COMPLETE locally; full Web tests and production build GREEN. Commit pending.**

**Global source:** `PLAN.md` F53 and panel UI inventory (`panel_ui/window.py`, `panel_ui/users.py`, `panel_ui/transactions.py`).

**Acceptance:** existing dashboard, transactions, receipts, budgets, products, analytics, and export screens remain reachable; a members screen lists only members authorized by the existing BFF; a manager can open transactions for a selected member; each user can edit their own profile; one refresh action and F5 invalidate Web queries; current route has active navigation state; labels work in RU/EN.

## Task 1: Capture gaps with tests

1. Test the members list, own-profile link, manager/member transaction scope, and error retry.
2. Add App integration tests for member-to-transaction navigation, active route state, refresh button, and F5.
3. Run focused tests and record observed RED before implementation.

## Task 2: Add the members route

1. Add `/family` with RU/EN member cards and role labels, using existing `GET /bff/tenants/{tenantId}/members`.
2. Link manager-selected member to `/transactions?memberId=...`; non-managers open own transactions without a widening filter.
3. Link only the signed-in user's card to their existing `/profile` editor. Do not add cross-member profile writes.
4. Read `memberId` from the transaction route and apply it to the existing transaction query.

## Task 3: Restore shared desktop-panel controls

1. Add a visible RU/EN refresh action that invalidates the query cache.
2. Bind F5 to the same refresh action and prevent browser reload.
3. Mark active navigation route and retain existing page modules.

## Task 4: Verify and commit

1. Run focused tests, full Web tests, TypeScript/Vite production build, and `git diff --check`.
2. Confirm existing transaction filters and viewer permissions remain intact.
3. Update `.agent/PROGRESS.md`; commit and push only F53 files plus plan/progress.

## Execution constraints

- Follow `PLAN → ACCEPTANCE → TESTS → OBSERVED RED → IMPLEMENT → GREEN → REGRESSION → VERIFY → COMMIT → PROGRESS`.
- UI visibility is convenience only; Core membership authorization remains authoritative.
- Preserve user-owned untracked paths.
