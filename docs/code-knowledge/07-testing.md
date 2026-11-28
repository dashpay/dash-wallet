# 7. Testing

thepastaclaw asks for a regression test with almost every blocking fix. It also checks that the test would fail on the
old code. Its reviews are static, and they say so; device and lifecycle validation stay with us.

## What to test

**7.1 Pin each fix with a regression test that fails on the old code, and rewrite tests that asserted the bug.** In
#1597 and #1595 the existing tests encoded the defect: the truncation, and both orderings' inconsistent results. A test
that encodes a bug locks the bug in.

**7.2 Put branchy UI and lifecycle decisions in pure functions or `*Policy` classes, and test the decision matrix.**
This avoids Hilt-injected activity tests. Examples:
- `homeBackAction(...)` + `HomeBackActionTest`
- `BlockchainResetShutdownPolicy` + 11 tests
- `degradedScreenActions(...)`
- `decideUnadvertisedDestination()`
- the sheet outcome as a pure reducer with `MixedFundsMigrationOrderingMatrixTest`

**7.3 Test through the real caller and the real helper, not only the extracted function or an injected stub.**
- A mock that throws bypasses the real catch.
- Mocking `currentProtocolVersion()` hid the SDK's cached-fallback bug (#1584).
- A test that fails on the marker write never exercises the primary-save failure (#1576).

Use an internal constructor with injectable collaborators. Assert both the result and that the dangerous collaborator
was never invoked.

**7.4 Test the real persistence path.**
- SQL: Robolectric with in-memory Room on the real schema (`SdkTxidHeightsQueryTest`, `AddressPoolBackfillDbTest` on
  the SDK's DashDatabase schema, `AssetLockKindResolverWalletScopeTest`).
- DataStore: a real one via Robolectric.
- Filesystem: real edge cases, such as a regular file used as the parent dir. Note that chmod-based unreadable-file
  tests pass falsely as root, so skip them when the chmod does not take effect.

**7.5 Exception-mapping tests use real serialized inputs**, for example an actual expired `PaymentRequest` (#1616).

**7.6 When the SDK engine owns the behaviour, record a device regression.** A unit test that only exercises a fake proves
nothing. Example: the int30 two-emulator procedure in #1614. Before a financial or privacy change ships, check new spend
destinations end to end through the real FFI on a disposable testnet wallet (#1570).

**7.7 When a contract changes, extend the fake to record call order and test it** (#1559 `FakeSource`).

**7.8 When a return type changes, update its tests by hand.** `assertEquals` on objects still compiles (#1605).

## How to make race tests deterministic

**7.9 A race regression must actually park the step it claims to cover.** In #1570 a "parked-pipeline-read" test never
parked a read and passed without testing the race.
- Use `CompletableDeferred` gates and `CoroutineStart.UNDISPATCHED`, not `delay(50)` against a 400 ms timeout.
- Fire an entry signal before launching the replacement.
- Join the specific job.

**7.10 Wait for a condition that the code under test makes true.** Never a fixed sleep, and never a condition that is
already true (`percentageSync == 100` held from the fixture). Use `withTimeout(5_000) { while (!cond) delay(5) }`, or a
trailing update as the barrier.

**7.11 Release latches in `finally`.** Otherwise a failed assertion hangs `scopeJob.join()` and the whole suite.

**7.12 Assert exact values.**
- Exact thresholds: a rollback test that started with one failure counted proved failure 6, not 5.
- Matched contents, not counts.
- Production constants, not mock copies (`UsernameRequest.VOTING_PERIOD_MILLIS`).

## Environment traps

**7.13 Tests must not depend on the build variant or on constructor-launched coroutines.**
- Pass policy explicitly (`cutoverEnabled = true`).
- Expose constructor jobs as `internal val initialSeeding: Job` and join them.
- Make debug short-circuits injectable: `BuildConfig.DEBUG` is true under `_testNet3Debug`, and it made a "disabled ⇒
  zero calls" test pass vacuously (#1606).

**7.14 CI green does not prove the flavoured `:wallet` unit tests ran.** The check job runs the unflavoured task. Run
locally with the variant the bot uses, e.g. `./gradlew :wallet:testDevnetDebugUnitTest` or `_testNet3Debug`. Robolectric
in `wallet` needs `conscryptMode=OFF` (#1609).

**7.15 Test harness scripts must fail the run.** `assert_log` ending in `printf` exited 0 after FAIL. Use a `FAILURES`
ledger plus an `EXIT` trap. Keep the scripts in sync with renamed log strings, and poll for forbidden lines until the
deadline (#1555, #1568).

## Naming
Name a test `subject_behaviour` after the scenario. Examples:
- `noteExternalSpend_marksDurably_withoutWaitingForTheSyncPass`
- `cancellationDuringFundingStillCompletesAndPersistsRawInviteLink`
- `aScanGateRefusal_retriesTheStartOnItsOwn_untilTheGateOpens`
- `rollback_clearsTheReceiveAddressOverlayAndTheBoundWallet`

The name must match what the test exercises.
