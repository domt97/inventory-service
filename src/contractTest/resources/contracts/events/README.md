# Copied contract schemas

Copies of `oms-workspace/architecture/contracts/events/*.schema.json` (ADR 0006). The workspace files are the
contract: when one of them changes, copy it here in the same piece of work.

- The six stock replies (published by this service), taken from oms-workspace commit `0a253e3` (last change to those
  files; `master` @ `43bbd05`).
- The three stock commands (consumed by this service: `ReserveStock`, `ConfirmStockReservation`,
  `ReleaseStockReservation`), taken from oms-workspace commit `a9a3efa` (last change to those files; `master` @ `9b5baea`).

Used by `StockReplyContractTest` and the `*CommandJsonTest` classes (unit tests) and by the integration tests, which
both put `src/contractTest/resources` on their classpath (`build.gradle`). The folder name is not a Gradle suite; a
future suite named `contractTest` would pick it up as its default resources folder.
