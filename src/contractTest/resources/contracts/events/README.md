# Copied reply schemas

Copies of `oms-workspace/architecture/contracts/events/*.schema.json` for the six stock replies (ADR 0006), taken
from oms-workspace commit `0a253e3` (last change to those files; `master` @ `43bbd05`). The workspace files are the
contract: when one of them changes, copy it here in the same piece of work.

Used by `StockReplyContractTest` (unit tests) and the integration tests, which both put `src/contractTest/resources`
on their classpath (`build.gradle`). The folder name is not a Gradle suite; a future suite named `contractTest` would
pick it up as its default resources folder.
