# Running Queries Monitor

DataSpell plugin that shows currently running Redshift queries in a popup.

## Features

- **Control (^) +.** opens a popup with running queries filtered by your user
- Switch between configured data sources via dropdown
- **Kill** — cancel a query with `pg_cancel_backend()` (inline confirmation, no modal dialogs)
- **Refresh** — reload the list
- **Escape** — close

## Screenshot

```
┌─ Running Queries ─────────────────────────────────────┐
│ Connection: [Redshift Prod ▼]                         │
│                                                       │
│  PID   │ Duration │ Query                             │
│  12345 │ 5s       │ select * from users where...      │
│  67890 │ 2m 13s   │ insert into events select...      │
│                                                       │
│  2 running on Redshift Prod         [Kill] [Refresh]  │
└───────────────────────────────────────────────────────┘
```

## Install from ZIP

1. Download `running-queries-1.0.0.zip` from [Releases](../../releases)
2. DataSpell → **Settings → Plugins → gear icon → Install Plugin from Disk**
3. Select the ZIP, restart DataSpell
4. Press **Control (^) +.**

## Build from source

Requires DataSpell 2025.3.x installed at `/Applications/DataSpell.app`. If your path differs, update `dataSpellPath` in `gradle.properties`.

```bash
./gradlew buildPlugin
```

Output: `build/distributions/running-queries-1.0.0.zip`

## How it works

Queries `stv_recents` on Redshift filtered by `current_user`:

```sql
select
    pid,
    trim(user_name) as user_name,
    starttime,
    datediff(second, starttime, getdate()) as duration_sec,
    substring(trim(query), 1, 200) as query_text
from stv_recents
where status = 'Running'
  and trim(user_name) = current_user
order by starttime desc
```
