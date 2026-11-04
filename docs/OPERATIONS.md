# Running it with real data

## 1. First-time setup for a real company

1. Copy `.env.example` to `.env`; set a long random `JWT_SECRET` and a real `SEED_PASSWORD`; keep `REQUIRE_STRONG_SECRETS=true`.
2. `docker compose up -d --build`, sign in as `admin`.
3. **Settings → Remove demo data** if you tried the sample files first (type `RESET` and enter your password). A snapshot of the data is written to `backups/` first, and training labels from the demo decisions are retired. Users, the audit log and the trained model are kept.
4. **Settings → Users**: add the real staff, give each a role, then delete the three seeded accounts (or at least change their passwords).
5. Load your data: **Upload** (customers, invoices, bank statement) or add single rows under **Invoices → New invoice** and **Payments → Record payment**.
6. **Dashboard → Run reconciliation**, then work the **Exceptions** queue.

## 2. Keeping data right day to day

| I need to… | Where |
|---|---|
| Add a bank statement | Upload → Bank statement (re-uploading the same file is safe) |
| Add one invoice / one payment | Invoices → New invoice / Payments → Record payment |
| Fix a wrong amount, date or name | Edit on the row (Admin and Accountant) |
| Remove a row entered by mistake | Delete on the row (Admin only) |
| Change a row the engine already allocated | Allocations are recalculated automatically after every edit |
| Change a row you decided by hand | Undo the decision first (Decisions or Credits tab); decided rows are locked on purpose |
| Add or remove staff, reset a password | Settings → Users (Admin) |

Every create, edit, delete, user change and reset is written to the `audit_log` table with who did it.

## 3. Backups

All data lives in the PostgreSQL volume `dbdata`. A volume is not a backup: it disappears with the machine.

```bash
scripts/backup.sh                          # macOS / Linux / Git Bash  -> backups/reconcile-<time>.dump
.\scripts\backup.ps1                       # Windows PowerShell
scripts/restore.sh backups/reconcile-<time>.dump     # asks you to type RESTORE; replaces everything
.\scripts\restore.ps1 backups\reconcile-<time>.dump
```

- Back up before every upgrade and before any **Remove demo data**. Run it from cron / Task Scheduler for a daily copy, and copy the file off the machine.
- The `backups/` folder is git-ignored: it contains real financial data, never commit it.
- Test a restore on a spare machine once. A backup you have never restored is a guess.
- The trained model and ML feedback live in `ml-service/data` (bind-mounted); copy that folder too.

## 4. Database schema changes (Flyway)

The schema is owned by Flyway migrations in `backend/src/main/resources/db/migration`.

- `V1__baseline_schema.sql` is the schema as it was when Flyway was added. A database that already existed is marked as V1 automatically (it is not recreated), so no data is lost on upgrade.
- To change the schema, add a **new** file `V3__short_description.sql` (next number). Never edit a file that has already run anywhere; Flyway checks and will refuse to start.
- `DDL_AUTO` defaults to `update` (Hibernate may add a missing column as a safety net). After the app has started cleanly on your database, set `DDL_AUTO=validate` in `.env`/compose so a mismatch between entities and tables stops the app at startup instead of failing later.
- Tests run on in-memory H2 and build the schema from the entities; Flyway is off there.

## 5. Known limits

- Sign-ins last 8 hours, but they are re-checked against the database on every request: deleting a user, changing their role, or setting/changing their password ends their existing sign-ins at once (the person signs in again). Changing your own password keeps you signed in on this browser only.
- Sign-in is throttled per username: 5 wrong passwords lock that name for 15 minutes (in memory, so a restart clears it). Someone who knows a username can therefore keep it locked; if that happens, restart the backend.
- The pre-reset snapshot (`backups/pre-reset-<time>.json.gz`) is a last-resort copy of the business tables as JSON. There is no restore button for it; `scripts/backup.sh` (a real `pg_dump`) is still the backup to rely on. The trained model cannot be un-trained: if demo decisions were already used to train it, re-train from base data after the reset.
- Deleting an invoice or payment is permanent (the audit log keeps who did it, not the row). Back up first if unsure.
- Matching accuracy figures in the README are measured on synthetic data. On real statements, start in shadow mode and compare with what your accountants do before trusting auto-matches.
