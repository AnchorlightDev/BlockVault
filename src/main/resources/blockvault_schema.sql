-- BlockVault  -  MySQL 8.0+ / MariaDB 10.5+
-- Column sizes checked against the real 26.2 target list:
--   longest block name = waxed_weathered_copper_golem_statue (35 chars)
--   Minecraft usernames are max 16 chars
-- InnoDB throughout: the plugin CONSUMES the submitted item, so every write
-- must be transactional. A lost write means a permanently lost block.

SET NAMES utf8mb4;

-- ---------------------------------------------------------------- people
CREATE TABLE IF NOT EXISTS bv_contributor (
  uuid          BINARY(16)      NOT NULL,
  last_name     VARCHAR(16)     NOT NULL,
  points        INT UNSIGNED    NOT NULL DEFAULT 0,
  blocks_given  SMALLINT UNSIGNED NOT NULL DEFAULT 0 COMMENT 'blocks this player unlocked (first donations)',
  items_given   INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT 'every item donated, first or repeat',
  profile       JSON            NULL COMMENT 'latest cached skin profile for heads',
  first_seen    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  last_seen     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP
                                ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (uuid),
  KEY idx_points (points DESC),
  KEY idx_name (last_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------- the target list
-- One row per block the edition expects. Seeded from vault_slots.json (built
-- by /bvsetup); re-seeded on start until the first donation, then immutable.
CREATE TABLE IF NOT EXISTS bv_target (
  material      VARCHAR(64)     NOT NULL,
  edition       VARCHAR(16)     NOT NULL,
  chapter       TINYINT UNSIGNED NOT NULL,
  rarity        ENUM('common','uncommon','rare') NOT NULL,
  section       ENUM('building','coloured','functional','natural','redstone') NOT NULL,
  head_x        INT NOT NULL, head_y  INT NOT NULL, head_z  INT NOT NULL,
  facing        VARCHAR(24)     NOT NULL,
  PRIMARY KEY (material, edition),
  KEY idx_chapter (edition, chapter)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------- submissions
-- The FIRST donation of each block: it unlocks the block and earns the rarity
-- points. material is the primary key, so the database decides who was first;
-- insert and catch the duplicate-key error instead of read-then-write.
CREATE TABLE IF NOT EXISTS bv_submission (
  material      VARCHAR(64)     NOT NULL,
  edition       VARCHAR(16)     NOT NULL,
  uuid          BINARY(16)      NOT NULL,
  points        SMALLINT UNSIGNED NOT NULL,
  profile       JSON            NULL COMMENT 'cached skin profile for the head',
  submitted_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (material, edition),
  KEY idx_uuid (uuid),
  KEY idx_time (submitted_at),
  CONSTRAINT fk_sub_target  FOREIGN KEY (material, edition)
      REFERENCES bv_target (material, edition),
  CONSTRAINT fk_sub_person  FOREIGN KEY (uuid)
      REFERENCES bv_contributor (uuid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------- donation tally
-- Every donation, first or repeat, summed per player per block. The niche head
-- shows whoever has given the most; ties go to whoever reached it first.
CREATE TABLE IF NOT EXISTS bv_donation (
  material      VARCHAR(64)     NOT NULL,
  edition       VARCHAR(16)     NOT NULL,
  uuid          BINARY(16)      NOT NULL,
  quantity      INT UNSIGNED    NOT NULL DEFAULT 0,
  first_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  last_at       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (material, edition, uuid),
  KEY idx_top (edition, material, quantity, last_at),
  KEY idx_uuid (uuid),
  CONSTRAINT fk_don_target  FOREIGN KEY (material, edition)
      REFERENCES bv_target (material, edition),
  CONSTRAINT fk_don_person  FOREIGN KEY (uuid)
      REFERENCES bv_contributor (uuid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------- chapters
CREATE TABLE IF NOT EXISTS bv_chapter (
  chapter       TINYINT UNSIGNED NOT NULL,
  edition       VARCHAR(16)     NOT NULL,
  title         VARCHAR(32)     NOT NULL,
  room          VARCHAR(32)     NOT NULL,
  seal_x        INT NULL, seal_y INT NULL, seal_z INT NULL,
  opens_at      DATETIME        NULL,
  opened_at     DATETIME        NULL,
  completed_at  DATETIME        NULL,
  completed_by  BINARY(16)      NULL,
  PRIMARY KEY (chapter, edition)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------- state
-- Whether the event is open (/bvstart). Lives here, not in config.yml, so a
-- config migration or a hand-edited config can never silently close the vault.
CREATE TABLE IF NOT EXISTS bv_state (
  edition       VARCHAR(16)     NOT NULL,
  started       TINYINT(1)      NOT NULL DEFAULT 0,
  started_at    DATETIME        NULL,
  -- finished is terminal: once set the vault is read-only for that edition and
  -- cannot be reopened. Set by /bvfinish, which also pays the leader bonus once.
  finished      TINYINT(1)      NOT NULL DEFAULT 0,
  finished_at   DATETIME        NULL,
  PRIMARY KEY (edition)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------- audit
-- Every state change. This is the reconstruction log if anything is ever lost.
CREATE TABLE IF NOT EXISTS bv_audit (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  ts            DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  actor         BINARY(16)      NULL,
  action        VARCHAR(32)     NOT NULL,
  material      VARCHAR(64)     NULL,
  detail        JSON            NULL,
  PRIMARY KEY (id),
  KEY idx_ts (ts),
  KEY idx_action (action)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------- weekly payouts
-- One row per completed weekly top-contributor payout. The primary key is the
-- idempotency guard: a payout for a (edition, week_key) is attempted with an
-- INSERT, and a duplicate-key means that week was already paid, so a restart or
-- a double trigger can never pay twice. week_key is the ISO week in the
-- configured timezone, e.g. '2026-W46'.
CREATE TABLE IF NOT EXISTS bv_payout_run (
  edition       VARCHAR(16)     NOT NULL,
  week_key      VARCHAR(10)     NOT NULL,
  run_at        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  total_points  INT UNSIGNED    NOT NULL DEFAULT 0,
  players       SMALLINT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (edition, week_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Audit line per paid block: who earned the weekly bonus on which block, for how
-- much, whether it was the featured block, and their lead over second place at
-- the moment the snapshot was taken.
CREATE TABLE IF NOT EXISTS bv_weekly_payout (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  edition       VARCHAR(16)     NOT NULL,
  week_key      VARCHAR(10)     NOT NULL,
  uuid          BINARY(16)      NOT NULL,
  material      VARCHAR(64)     NOT NULL,
  points        SMALLINT UNSIGNED NOT NULL,
  featured      TINYINT(1)      NOT NULL DEFAULT 0,
  lead_margin   INT             NOT NULL DEFAULT 0,
  paid_at       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_week (edition, week_key),
  KEY idx_uuid (uuid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------- featured block
-- The featured block(s). A "set" is all rows sharing one set_at; the CURRENT
-- featured block(s) are the set with the greatest set_at, so a rotation (or a
-- staff override) takes effect the instant it is written. set_at is millisecond
-- precision and written explicitly so every row of one set shares it exactly.
-- week_key records which payout chose the set (audit only). set_by is null for
-- an automatic pick, else the staff member who overrode it.
CREATE TABLE IF NOT EXISTS bv_featured (
  edition       VARCHAR(16)     NOT NULL,
  week_key      VARCHAR(10)     NOT NULL,
  material      VARCHAR(64)     NOT NULL,
  set_at        DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  set_by        BINARY(16)      NULL,
  PRIMARY KEY (edition, week_key, material),
  KEY idx_setat (edition, set_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------- contribution log
-- Append-only monitoring log of every contribution: who, what, how much, how it
-- was submitted, and whether it was the unlocking donation. Distinct from the
-- bv_donation tally (which is summed) and bv_audit (state changes). Staff review
-- this to spot item-passing or collusion; nothing is blocked automatically.
CREATE TABLE IF NOT EXISTS bv_contribution_log (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  edition       VARCHAR(16)     NOT NULL,
  uuid          BINARY(16)      NOT NULL,
  material      VARCHAR(64)     NOT NULL,
  amount        INT             NOT NULL,
  -- chest/hand/new = player donations; adjust/remove = staff edits via /bvmonitor
  method        ENUM('chest','hand','new','adjust','remove') NOT NULL,
  first_unlock  TINYINT(1)      NOT NULL DEFAULT 0,
  ts            DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_uuid (uuid),
  KEY idx_mat (edition, material),
  KEY idx_ts (ts)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------- views
CREATE OR REPLACE VIEW bv_progress AS
SELECT t.edition,
       t.chapter,
       COUNT(*)                AS total,
       COUNT(s.material)       AS collected,
       ROUND(100 * COUNT(s.material) / COUNT(*), 1) AS pct
FROM bv_target t
LEFT JOIN bv_submission s
       ON s.material = t.material AND s.edition = t.edition
GROUP BY t.edition, t.chapter;

-- Only players with points: a contributor row can exist with 0 (repeat-only
-- donor, revoked unlock), and they shouldn't fill leaderboard places.
CREATE OR REPLACE VIEW bv_leaderboard AS
SELECT c.uuid, c.last_name, c.points, COUNT(s.material) AS blocks
FROM bv_contributor c
LEFT JOIN bv_submission s ON s.uuid = c.uuid
WHERE c.points > 0
GROUP BY c.uuid, c.last_name, c.points
ORDER BY c.points DESC, blocks DESC;
