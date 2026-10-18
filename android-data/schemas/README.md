# Room schema export

`android-data/build.gradle.kts` passes `room.schemaLocation` to KSP, so every compiled version of
the database writes its `CREATE` statements here as `<version>.json`.

These files are the reference `DatabaseMigrations` is written and reviewed against. They are
**not** hand-edited, and a change to any entity or DAO that changes the schema must produce a new
file plus an explicit `Migration` in `DatabaseMigrations.kt` — `fallbackToDestructiveMigration()` is
deliberately never called, because the `candles` table is write-once history and `outcomes` is the
app's published accuracy record (plan.md §5.2, §6.1).

Versions so far:
* `1` — the plan.md §5.2 table set.
* `2` — additive: `statement_rows`, `derived_metrics`, `watchlist`. See `MIGRATION_1_2`.
* `3` — additive: `ratios.industry`. See `MIGRATION_2_3`.
* `4` — additive: `index_candles`, the benchmark series. See `MIGRATION_3_4`.
* `5` — additive: `journal_entry`, the reader's own record. See `MIGRATION_4_5`.
* `6` — additive: `decision_counter`, the on-device projection-gate tally (gap F3). See
  `MIGRATION_5_6`.

Because no Android SDK is available on the build machine, `:android-data` is excluded from the
build here and these files cannot be generated yet. They will appear the first time the module is
compiled with an SDK present. **This has since happened in CI** (`VERIFICATION.md` §5 records run
38132317377 as the first green `:android-data` compile), so the exported JSON is produced by the
`android` job on every push; only `2.json` is committed, and it is kept as the historical reference
for `MIGRATION_1_2`.