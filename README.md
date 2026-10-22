# BlockVault

![img](https://i.imgur.com/DPcMoaq.png)

BlockVault drives a purpose-built museum for a twelve-month, server-wide block
collection event. Players donate one of every obtainable block in Minecraft
Java **26.2**; each donation is consumed and recorded, a donor head appears on
the shelf, and six themed floors unlock over the season.

## Requirements

| | |
|---|---|
| Minecraft | Java Edition **26.2** (`api-version '26.2'`) |
| Server | Paper |
| Build JDK | **25** (paper-api 26.2 ships Java 25 bytecode) |
| Database | MySQL 8.0+ / MariaDB 10.5+ |
| Package | `dev.anchorlight.blockvault` |

HikariCP and the MySQL driver are pulled at runtime via `libraries:` in
`plugin.yml` — no shading.

## Setup

1. **Apply the schema.** Run `src/main/resources/blockvault_schema.sql` against
   your database before first start.
2. **Generate the data artefacts** from section 12 of the implementation brief:
   ```
   python tools/gen_artifacts.py       # reads tools/target_list.txt
   ```
   This produces `src/main/resources/vault_items.yml`,
   `src/main/resources/vault_slots.json` and `tools/spawn_frames.mcfunction`.
   The plugin **disables itself** if `vault_slots.json` is missing — it never
   regenerates the target list.
3. **Paste the schematic** `sky_vault.schem` at the world origin, then run
   `spawn_frames.mcfunction` standing on that origin to spawn the empty frames.
4. **Configure** `config.yml` (git-ignored — holds DB credentials):
   database connection, the world `origin:` the schematic sits at, the chapter
   `opens-at` dates, and optionally a `discord.webhook-url`.
5. Start the server. Bootstrap seeds `bv_target` / `bv_chapter` from the manifest
   and runs a three-way validation pass (manifest ↔ `vault_items.yml` ↔
   `bv_target`) plus new-block detection.
6. `/bvstart` opens the event.

## How it works

- **Submissions are transactional.** Order is always write → confirm commit →
  consume the item. If the database is unreachable the block stays in the
  player's hand. `bv_submission.material` is the primary key, so one-of-each is
  enforced by the database and simultaneous submits race safely.
- **The head is the state indicator.** No separate colour-glass state block.
  `/bvupdatestate` reconciles the world against the database and only ever writes
  the manifest `head` cells — never structure — in a single summary line.
- **Locked floors are empty.** Shelf frames and signs on a chapter that hasn't
  opened hold nothing, so freecam or x-ray reveals nothing. On unlock (and on
  every reconcile pass) the plugin fills each frame with its target item and
  writes the block name + rarity on the sign. `/bvfind`, `/bvinfo`,
  `/bvhistory` and `/bvmissing` also hide locked floors unless the sender has
  `blockvault.spoilers` (op by default).
- **Chapters** open on their scheduled date *or* at 90% of the previous chapter,
  whichever comes first. Earlier chapters never close. Unlock breaks one
  `iron_bars` seal and runs a broadcast/title/fireworks/BossBar ceremony.
- **The target list is frozen per `edition`.** New blocks go in as a new edition,
  never the running one.
- **Chapter advancements** ship as a bundled datapack, written into
  `<world>/datapacks/blockvault/` on load and granted by the plugin when a
  chapter reaches 100%. A world reload may be needed the first time.

## The six floors

The season runs across six themed floors, one per chapter. Each unlocks on its
scheduled date or when the previous chapter reaches 90%, whichever comes first;
earlier floors never close. Completing a floor (100%) grants its advancement
from the bundled datapack.

| # | Floor | Advancement | Opens | Icon |
|---|---|---|---|---|
| 1 | The Undercroft | Foundations | 2026-01-01 (`/bvstart`) | Chiseled Stone Bricks |
| 2 | The Conservatory | Green & Growing | 2026-02-15 | Oak Sapling |
| 3 | The Deep | Into the Deep | 2026-05-15 | Deepslate Bricks |
| 4 | The Gallery | Every Colour | 2026-08-01 | Pink Wool |
| 5 | The Forge | The Nether | 2026-09-15 | Nether Bricks |
| 6 | The Observatory | The End | 2026-12-01 | Purpur Block |

Chapter 1 opens with `/bvstart` regardless of its date. Chapter 6's advancement
uses the `challenge` frame; the rest use `goal`. Open dates come from the
`chapters:` block in `config.yml` and are re-read on every start, so you can
adjust a future floor mid-season. Floors that haven't opened are empty, and
query commands hide them unless the sender has `blockvault.spoilers`.

## Commands

| Command | Permission | Purpose |
|---|---|---|
| `/bvsubmit` | `blockvault.submit` | Donate the held block (in-region, no creative) |
| `/bvprogress` | `blockvault.progress` | Current-chapter bar + overall bar |
| `/bvleaderboard [month]` | `blockvault.leaderboard` | Top 10 all-time, or this month |
| `/bvfind <block>` | `blockvault.find` | Level, room, shelf coordinates |
| `/bvinfo <block>` | `blockvault.info` | Rarity, points, chapter, status |
| `/bvmissing [chapter] [page]` | `blockvault.missing` | Outstanding list, paginated |
| `/bvcheck` | `blockvault.check` | What in your inventory the vault needs |
| `/bvme` | `blockvault.me` | Your blocks, points, rank |
| `/bvhistory <block>` | `blockvault.history` | Who donated it and when |
| `/bvedition` | `blockvault.edition` | Frozen target-list version |
| `/bvstart [stop]` | `blockvault.start` | Open / close the event |
| `/bvupdatestate` | `blockvault.updatestate` | Reconcile world ↔ database (console-safe) |
| `/bvreload` | `blockvault.reload` | Reload config without restart |
| `/bvrevoke <block>` | `blockvault.revoke` | Reverse a submission, refund points, remove head |
| `/bvrepair` | `blockvault.repair` | Rebuild missing frames and heads from the manifest |
| `/bvbackup` | `blockvault.backup` | Timestamped `mysqldump` |

`blockvault.build` bypasses in-region build/interaction protection.
`blockvault.spoilers` lets query commands show blocks on locked floors.
