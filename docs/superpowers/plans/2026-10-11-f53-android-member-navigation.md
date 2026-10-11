# F53 Android member-to-transactions navigation

**Authority:** `PLAN.md` F53 and `.agent/PROGRESS.md` F59 Android parity. This is a focused Android counterpart; the global F53 Web work remains complete as recorded under E4.92.

**Approved behavior:** owners/admins get an Android «Участники» screen populated only from the already-loaded, Core-authorized tenant member list. Tapping a member opens «Операции» filtered by that member's exact ID. No API, authorization, or transaction semantics change; non-manager roles do not get the family member list.

## TDD steps

1. Add Compose tests proving a manager can navigate from a member row to that member's expenses and a viewer cannot access the member list. Run both before production edits and record RED.
2. Add a role-gated members destination that reuses `FinanceUiState.transactionMembers`; member selection sets the existing transaction filter and opens the existing transaction screen.
3. Run focused tests on the isolated API-27 emulator, then the Android JVM and relevant screen regression suites. Update F53 parity evidence and `.agent/PROGRESS.md`; preserve F52 partial/live deployment limits.
4. Obtain independent review, commit only this F53 slice, push the authorized feature branch, and verify remote HEAD.

## Acceptance

- Owner/admin can see Core-provided members and tap one to open their transaction list; the existing filter callback receives that exact member ID.
- Viewer cannot open a family member list or widen transaction scope.
- The selected Android route is visually and accessibly indicated, including when member selection navigates to filtered transactions.
- Existing own-profile edit and transaction filtering remain unchanged.
- No live OIDC/Core authorization or production member-data claim is inferred from mock Compose coverage.

## Active-route follow-up

- Keep the existing route `TextButton` and its full hit target. Add a stable tag and selected semantics, plus a distinct Material selected color treatment, derived from the same `activeScreen` state that chooses the visible content.
- Verify overview selection on entry, selected-state movement after tab navigation, and member-to-transactions navigation preserving the exact member filter while moving the selected state to Transactions. Viewer still has no member route.
- Run the full `FinanceScreensTest` instrumentation class on the isolated API-27 emulator and record F53 as Android-partial while refresh semantics and live Core/OIDC remain open.
