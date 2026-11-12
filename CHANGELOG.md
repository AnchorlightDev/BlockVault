# Changelog

All notable changes to BlockVault are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.3.0] - 2026-11-12

### Added

- **Weekly top-contributor bonus.** Every week (default Friday 9am Sydney, follows
  daylight saving) the current top contributor of each unlocked block earns points,
  so the competition keeps going between floor drops. 2 points per block, capped at
  10 blocks per player per payout (20 points max); if a player leads more than 10,
  the paid 10 are those where their lead over second place is biggest. The cap is
  the same for everyone, including the overall leader, and resets each week. Every
  payout line is logged to MySQL (`bv_weekly_payout`). Configure under
  `weekly-bonus`. Staff can trigger or inspect it with `/bvweekly [run|status]`.
- **Weekly featured block.** One block (configurable count) is featured each week,
  rotated at the Friday payout and announced with it. It is chosen from unlocked
  floors, favouring blocks with few or no contributions. While featured, its
  first-contributor (unlock) points are doubled if still unclaimed, and its weekly
  top-contributor bonus is 4 points instead of 2; it does not count toward the
  10-block cap. Staff override with `/bvfeatured set <block>` / `clear`. Shown on
  the info board via the new `{featured}` placeholder.
- **Payout announcements.** After each payout a summary is broadcast in-game and
  posted to Discord (via `discord.webhook-url`): who earned bonus points, how much,
  the top movers, and next week's featured block. Paid players also get a personal
  message with their block count and whether they hit the cap
  (`weekly-bonus.announce-personal`).
- **Contribution monitoring.** Every contribution is logged (player, block, amount,
  submission method, timestamp) to `bv_contribution_log`. Staff review and correct
  with `/bvmonitor history <player>`, `recent [n]`, `adjust <player> <block> <delta>`
  and `remove <player> <block>`. Monitoring only - nothing is blocked automatically.
  Toggle logging with `monitoring.enabled`.

- **`/bvblocks` block browser.** A chest menu of every block on the open floors,
  showing whether it's still needed, its rarity and points, the current top
  contributor, how many you've given and how many more you'd need to take the top
  spot. Filter by floor and by status (all / still needed / unlocked / blocks you top /
  in your inventory), page through, and click a block to see where its niche is.
  Featured blocks and blocks you top glow. `/bvblocks [needed|unlocked|top|carrying] [floor]`
  opens it pre-filtered; alias `/bvbrowse`. Locked floors stay hidden (`blockvault.spoilers`).

- **Featured block is set automatically.** If the vault is open and no featured
  block is set (the first week of a season, or after `/bvfeatured clear` removes the
  last one), one is picked within a minute using the weekly rotation's rule (fewest
  contributions first) and announced in-game and on Discord. The weekly payout
  still rotates it each week.

### Changed

- **Tie-breaking is explicit everywhere.** On an equal amount of a block, the top
  contributor is whoever reached that amount first (earliest timestamp). This was
  already the live rule; it is now unit-tested and used for the weekly bonus too.
- **Niche labels are clearer and thinner on the wall.** Labels are two lines (block
  name + current top contributor) in light colours, full-bright, with a configurable
  translucent background, and a reduced view range so distant labels stop stacking
  into a wall of text. New keys: `display.label-view-range`, `display.label-brightness`,
  `display.label-background`. They stay flat on the wall (billboard FIXED).
- **End-of-season leader bonus is off by default** (`points.leader-bonus: 0`); the
  weekly bonus now rewards top contributors. `/bvfinish` and its Discord post no
  longer mention a bonus when it is 0. The info board shows the weekly rate and cap
  via new `{weekly}` and `{cap}` placeholders.
- **`/bvweekly run` needs `confirm`.** A manual run counts as that week's payout, so
  it now warns and asks for `/bvweekly run confirm`.
- **`/bvmonitor` finds players by the name they donated under** (from the vault's own
  records, so offline donors work too), logs `adjust`/`remove` edits to
  `bv_contribution_log`, and refreshes the niche heads straight away.

### Fixed

- **`/bvsubmit hand` took the items after recording the donation**, so a stack
  dropped or moved away in between was still credited. Items are now taken first and
  handed back if the write fails.
- **Featured unlocks stored the undoubled points** on the unlock record, so
  `/bvrevoke` refunded too little and `/bvhistory` / the monthly leaderboard were
  wrong. The record now holds the points actually paid.
- **Daily and weekly schedules drifted.** They ran on fixed tick periods, which run
  late under lag (about 19 hours a week at 18 TPS) and ignored daylight saving. Both
  now follow the real clock, checked once a minute, and pick up `/bvreload` changes.
- **The first restart could pay a week the vault wasn't open for.** A missed payout
  is now only caught up if its slot was after the vault was opened (`bv_state.started_at`).
- **Info board showed last week's featured block** after a payout (and "-" right after
  startup) until something else redrew it.
- **Items returned to an offline player were lost on restart.** They are now kept in
  `owed_items.yml` until the player rejoins.
- **Exact ties are ordered the same in SQL and Java** (uuid as the final tie-break).
- **Leaderboards no longer list players with 0 points.**
- **Closing the donation chest by disconnecting, or during a shutdown/reload, could
  lose its contents** (the async write might never finish). The items now go straight
  back into the player's inventory instead, and open chests are closed on disable.
- **Display refreshes are coalesced** - a burst of donations ran a full ~10-query
  refresh per donation; calls within a second now share one pass.
- **Chapter completion was posted to Discord as "is now open!"** - it now has its own
  message.
- **`/bvbackup` could hang** if mysqldump wrote a lot to stderr (never read). stderr now
  goes to a file and its first line is shown when a backup fails.
- **Turning featured blocks off didn't fully turn them off** - the last pick still
  glowed, showed on the board and paid the featured rate at the payout. With
  `featured-block.enabled: false` nothing is featured, and `/bvfeatured set` says so.
- **`/bvblocks` buttons stopped working** if another plugin cancelled the click first.
- **`featured-block.double-first-contributor` was ignored** - featured unlocks always
  paid double. The setting (and `featured-block.enabled`) now applies.

## [1.2.0] - 2026-11-08

### Added

- **Daily leaderboard post to Discord.** Once a day, at a configured hour in the
  plugin timezone, the current top contributors are posted to the Discord webhook.
  Configure under `discord.daily-leaderboard` (`enabled`, `hour` 0-23, `top`). Needs
  `discord.webhook-url` set; skipped while the vault is closed or finished.

## [1.1.0] - 2026-11-07

### Added

- **Open/closed status on the lobby hologram.** Shows `● OPEN`, `● CLOSED` or
  `SEASON COMPLETE` under the title, and `/bvstart` / `/bvstart stop` refresh it at once.

### Changed

- **The "vault still needs…" hint only shows inside the vault.** The held-block
  action-bar nudge now fires only while you're within the vault region (the same
  area `/bvsubmit` requires), instead of anywhere on the map, so it no longer nags
  you about blocks you can't submit from where you are.
- **Holograms can be repositioned mid-season.** `/bvsetup hologram <2-6|info>` and
  `/bvsetup lobby [head|name|count|title]` are now allowed while the vault is open,
  since they only move cosmetic displays and never touch the frozen niche layout.
  Run `/bvreload` after moving one. Everything remains frozen once the season is
  finished (read-only).

### Fixed

- **Blocks from unopened floors can no longer be donated.** `/bvsubmit` (chest),
  `/bvsubmit new` and `/bvsubmit hand` now reject blocks whose floor hasn't opened
  yet and hand the items back, instead of silently recording them, awarding points
  and firing the rare-donation webhook. Closes a spoiler/fairness hole where players
  could blind-donate future-floor blocks and lock in early donor credit.
- **Lobby leader head clears when there are no donations** (e.g. after a reset)
  instead of keeping the previous leader's skin.
- **Sealed-floor blocks no longer prompt players.** The "vault still needs…" action-bar
  hint and `/bvcheck` now skip blocks on floors that haven't opened, which the vault
  refuses anyway, and no longer spoil what's on those floors.

## [1.0.0] - 2026-11-07

First public release. BlockVault drives a purpose-built museum for a twelve-month,
server-wide block collection event on Paper (Minecraft Java **26.2**). Players
donate one of every obtainable block; each donation is consumed and recorded, a
donor head appears on the shelf, and six themed floors unlock over the season.

### Added

- **Donations.** `/bvsubmit` opens the donation chest, `/bvsubmit new` bulk-donates
  one of every new block you carry, and `/bvsubmit hand [amount]` donates the block
  in hand. Donations are consumed, recorded, and scored by rarity/points.
- **Progress and discovery.** `/bvprogress` (chapter and overall progress with a
  progress bar), `/bvmissing [chapter] [page]`, `/bvfind <block>`, `/bvinfo <block>`,
  `/bvcheck` (scan your inventory for blocks the vault needs), and `/bvedition`
  (the frozen target-list version).
- **Players.** `/bvme` (your donations, points and rank), `/bvhistory <block>`
  (who donated a block and when), and `/bvleaderboard [month]` (top contributors,
  all-time or monthly).
- **Season lifecycle.** `/bvstart [stop]` opens or pauses the event, and
  `/bvfinish [confirm]` ends the season terminally, makes the vault read-only, and
  pays each block's leader a bonus.
- **Six themed floors** that unlock on configurable `opens-at` dates across the
  season, with spoiler controls hiding unopened floors in `/bvfind`, `/bvinfo`
  and `/bvmissing` (bypassable with `blockvault.spoilers`).
- **In-world setup.** `/bvsetup` builds the vault layout by scanning each floor's
  heads with a wand, including corners, seals, lobby leader head, holograms and a
  status check. Floor bounds persist to `floor_bounds.yml`.
- **Admin tooling.** `/bvupdatestate` (reconcile the world display against the
  database), `/bvreload` (hot-reload config), `/bvrevoke <block>` (reverse a
  submission and refund points), `/bvrepair` (rebuild missing frames and heads from
  the manifest), and `/bvbackup` (timestamped database dump).
- **Live world display.** Donor heads, item frames and leaderboard displays update
  dynamically; a scheduler reconciles the in-world state with the database.
- **Region protection.** Griefing and environmental-change protection around the
  vault, with `blockvault.build` to bypass for staff.
- **Discord webhooks** for rare submissions, chapter unlocks and season finish
  (with leader bonuses).
- **Persistence.** MySQL/MariaDB schema applied on boot, HikariCP connection
  pooling, and audit logging of submissions and admin actions.

[1.1.0]: https://github.com/AnchorlightDev/BlockVault/releases/tag/v1.1.0
[1.0.0]: https://github.com/AnchorlightDev/BlockVault/releases/tag/v1.0.0
