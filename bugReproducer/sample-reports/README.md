# Bug reproducer sample reports

These two persisted client reports are intentionally small, reproducible examples:

- `client_bug_reports/Client_bug_conflict.json` produces a merge conflict in `ShipmentTracking.kt`.
- `client_bug_reports/Client_bug_compilation-failure.json` applies an invalid Kotlin line and fails during compilation.

Run the launcher with this directory as its bug directory:

```shell
BUG_DIRECTORY=bugReproducer/sample-reports ./gradlew :bugReproducer:run
```
