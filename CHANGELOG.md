# Changelog

All notable changes to BlockVault are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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

[1.0.0]: https://github.com/AnchorlightDev/BlockVault/releases/tag/v1.0.0
