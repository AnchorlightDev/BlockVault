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
`plugin.yml`. StoneLib is shaded into the jar; it isn't in a public repository,
so `mvn install` StoneLib 1.3 locally before building. `paper-api` is capped
below 26.3 so the build can't pick up a newer API.

## Setup

1. **Apply the schema.** Run `src/main/resources/blockvault_schema.sql` against
   your database before first start (the plugin also applies it on boot).
2. **Configure** `config.yml` (git-ignored — holds DB credentials): database
   connection, the world `origin:` (any fixed point near the vault — every
   stored position is relative to it), the chapter `opens-at` dates, and
   optionally a `discord.webhook-url`.
3. **Start the server.** With no layout yet, the plugin starts in *setup mode*
   and only `/bvsetup` works.
4. **Scan each floor in-game** (before `/bvstart`):
   ```
   /bvsetup wand              get the setup wand (blaze rod), then:
                                left-click one corner block   -> pos1
                                right-click the opposite one  -> pos2
                              (or stand on each corner: /bvsetup pos1 / pos2)
   /bvsetup floor 1           scan + preview each block and its name
   /bvsetup confirm           (or /bvsetup cancel)
   /bvsetup seal 2            looking at floor 2's iron-bar seal (repeat 2-6)
   /bvsetup lobby             looking at the lobby leader head (optional)
   /bvsetup status            check every floor is done
   ```
   Wall heads in the selection are **niches**. They're ordered clockwise from
   north around the floor's centre, top to bottom, and given blocks from
   `floor_assignment.tsv` in list order, so each section fills a continuous run
   of wall. Extra niches are kept as spares. Standing heads are that floor's
   **leader pedestals**, ranked #1, #2… in the same clockwise order. The layout
   is saved to `plugins/BlockVault/vault_slots.json` (previous copy kept as
   `.bak`).
5. **Restart.** Bootstrap seeds `bv_target` / `bv_chapter` from the layout
   (re-seeded on every start until the first donation) and runs validation.
6. `/bvstart` opens the event. The layout is frozen from then on. The open/closed
   state is stored in the database (`bv_state`), not `config.yml`. No floor opens
   — by date or by 90% — until `/bvstart`; at that point any floor whose `opens-at`
   has already passed opens immediately, so use future dates on a test server.

`floor_assignment.tsv` (tab-separated `block chapter rarity section edition`)
is bundled in the jar; drop an edited copy in the plugin folder to override it.
Blocks from another edition (e.g. 26.3) get niches but are **reserved**: plain
heads, no item, not counted in the season total.

## How it works

- **First donation unlocks, most donations wins the head.** The first player
  to donate a block unlocks it and earns its rarity points. Anyone can keep
  donating more of it afterwards (no extra points); the niche head wears the
  skin of whoever has donated the most, ties going to whoever got there first.
- **Submissions are transactional.** Order is always write → confirm commit →
  consume. If the database is unreachable the items stay in the player's hand.
  `bv_submission.material` is the primary key, so the database decides who was
  first and simultaneous donors race safely. `/bvsubmit` gives the whole held
  stack; `/bvsubmit <n>` gives part of it. Container items with contents are
  refused.
- **Niches are heads + the block and its name.** On open floors the block is
  shown as a small item on the face of the block directly below each niche head,
  with its name underneath (`display.label` / `label-scale` in config). Locked floors show plain
  heads and no items, so freecam or x-ray reveals nothing. `/bvfind`, `/bvinfo`,
  `/bvhistory` and `/bvmissing` also hide locked floors unless the sender has
  `blockvault.spoilers` (op by default).
- **Floor leaders.** Each floor's standing heads show its top donors by total
  items given to that floor's blocks, with a #rank / name / count label.
- `/bvupdatestate` reconciles the world against the database and only ever
  writes niche head skins and item displays — never structure.
- **Chapters** open on their scheduled date *or* at 90% of the previous chapter,
  whichever comes first. Earlier chapters never close. Unlock breaks one
  `iron_bars` seal and runs a broadcast/title/fireworks/BossBar ceremony.
- **The target list is frozen per `edition`.** New blocks go in as a new edition,
  never the running one.

## The six floors

The season runs across six themed floors, one per chapter, numbered from the
bottom up: Floor 1 (The Undercroft) is the lowest level and Floor 6 (The
Observatory) is the top. Each floor's seal is the iron bars on the way up into it. Each unlocks on its
scheduled date or when the previous chapter reaches 90%, whichever comes first;
earlier floors never close. Completing a floor (100%) is announced in chat and
on the Discord webhook.

| # | Floor | Chapter | Opens |
|---|---|---|---|
| 1 | The Undercroft | Foundations | 2026-01-01 (`/bvstart`) |
| 2 | The Conservatory | Green & Growing | 2026-02-15 |
| 3 | The Deep | Into the Deep | 2026-05-15 |
| 4 | The Gallery | Every Colour | 2026-08-01 |
| 5 | The Forge | The Nether | 2026-09-15 |
| 6 | The Observatory | The End | 2026-12-01 |

Chapter 1 opens with `/bvstart` regardless of its date. Open dates come from the
`chapters:` block in `config.yml` and are re-read on every start, so you can
adjust a future floor mid-season. Floors that haven't opened are empty, and
query commands hide them unless the sender has `blockvault.spoilers`.

## Commands

| Command | Permission | Purpose |
|---|---|---|
| `/bvsubmit [amount]` | `blockvault.submit` | Donate the held block — whole stack by default (in-region, no creative) |
| `/bvprogress` | `blockvault.progress` | Current-chapter bar + overall bar |
| `/bvleaderboard [month]` | `blockvault.leaderboard` | Top 10 all-time, or this month |
| `/bvfind <block>` | `blockvault.find` | Level, room, shelf coordinates |
| `/bvinfo <block>` | `blockvault.info` | Rarity, points, chapter, status |
| `/bvmissing [chapter] [page]` | `blockvault.missing` | Outstanding list, paginated |
| `/bvcheck` | `blockvault.check` | What in your inventory the vault needs |
| `/bvme` | `blockvault.me` | Blocks unlocked, items given, points, rank |
| `/bvhistory <block>` | `blockvault.history` | Who unlocked it, and the top 5 donors |
| `/bvedition` | `blockvault.edition` | Frozen target-list version |
| `/bvstart [stop]` | `blockvault.start` | Open / close the event |
| `/bvupdatestate` | `blockvault.updatestate` | Reconcile world ↔ database (console-safe) |
| `/bvreload` | `blockvault.reload` | Reload config without restart |
| `/bvrevoke <block>` | `blockvault.revoke` | Reverse a block: clear every donation of it, refund the unlock points |
| `/bvrepair` | `blockvault.repair` | Rebuild missing niche heads and item displays |
| `/bvsetup …` | `blockvault.setup` | Build the layout by scanning each floor (before `/bvstart`) |
| `/bvbackup` | `blockvault.backup` | Timestamped `mysqldump` |

`blockvault.build` bypasses in-region build/interaction protection.
`blockvault.spoilers` lets query commands show blocks on locked floors.
