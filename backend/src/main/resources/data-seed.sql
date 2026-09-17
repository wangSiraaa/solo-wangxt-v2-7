-- Demo seed data (H2 syntax; also parsed by MySQL, see data-seed-mysql.sql copy).
-- Timestamps are UTC. Passwords: operator -> operator123, players -> player123
INSERT INTO app_user (id, username, password_hash, role, display_name, created_at) VALUES
 (1, 'ops_admin', '$2a$10$kGD2j0PdX5xSY5x15rGk3e8jh.vPVwPf76CBITKqCspXVnMlNe/kC', 'OPERATOR', '运营主管', TIMESTAMP '2026-09-01 00:00:00'),
 (2, 'player1',   '$2a$10$X/3kWibERI6kKA1JjaQMDuSFBF7Jt7FTsV/NYL.zCEucCMEZh3YZ6', 'PLAYER',   '玩家一号', TIMESTAMP '2026-09-01 00:00:00'),
 (3, 'player2',   '$2a$10$X/3kWibERI6kKA1JjaQMDuSFBF7Jt7FTsV/NYL.zCEucCMEZh3YZ6', 'PLAYER',   '玩家二号', TIMESTAMP '2026-09-01 00:00:00'),
 (4, 'player3',   '$2a$10$X/3kWibERI6kKA1JjaQMDuSFBF7Jt7FTsV/NYL.zCEucCMEZh3YZ6', 'PLAYER',   '玩家三号', TIMESTAMP '2026-09-01 00:00:00');

-- Recipes
INSERT INTO recipe (id, code, name, status, created_by, created_at, updated_at) VALUES
 (1, 'FIRE_SWORD',   '烈焰之剑', 'ACTIVE', 1, TIMESTAMP '2026-09-01 00:00:00', TIMESTAMP '2026-09-01 00:00:00'),
 (2, 'THUNDER_BOW',  '雷霆之弓', 'ACTIVE', 1, TIMESTAMP '2026-09-01 00:00:00', TIMESTAMP '2026-09-01 00:00:00'),
 (3, 'OLD_AMULET',   '旧日护符', 'CLOSED', 1, TIMESTAMP '2026-08-01 00:00:00', TIMESTAMP '2026-08-20 00:00:00');

-- v1 published recipes (the immutable snapshots players actually craft against)
INSERT INTO recipe_version
 (id, recipe_id, version_no, status, inputs_json, outputs_json, start_time, end_time, craft_timeout_s, published_at, published_by, created_at)
VALUES
 (1, 1, 1, 'PUBLISHED',
  '[{"itemCode":"MAT_IRON","qty":3},{"itemCode":"MAT_MAGIC_CORE","qty":2},{"itemCode":"MAT_FIRE_SHARD","qty":1}]',
  '[{"itemCode":"EQP_FIRE_SWORD","qty":1},{"itemCode":"GOLD","qty":100}]',
  TIMESTAMP '2026-09-01 00:00:00', TIMESTAMP '2026-10-31 23:59:59', 120,
  TIMESTAMP '2026-09-01 00:00:00', 1, TIMESTAMP '2026-09-01 00:00:00'),
 (2, 2, 1, 'PUBLISHED',
  '[{"itemCode":"MAT_WOOD","qty":2},{"itemCode":"MAT_MAGIC_CORE","qty":1}]',
  '[{"itemCode":"EQP_THUNDER_BOW","qty":1}]',
  TIMESTAMP '2026-09-10 00:00:00', TIMESTAMP '2026-12-31 23:59:59', 120,
  TIMESTAMP '2026-09-10 00:00:00', 1, TIMESTAMP '2026-09-10 00:00:00'),
 (3, 3, 1, 'ARCHIVED',
  '[{"itemCode":"MAT_WOOD","qty":1}]',
  '[{"itemCode":"EQP_OLD_AMULET","qty":1}]',
  TIMESTAMP '2026-08-01 00:00:00', TIMESTAMP '2026-08-15 23:59:59', 120,
  TIMESTAMP '2026-08-01 00:00:00', 1, TIMESTAMP '2026-08-01 00:00:00');

-- Inventory.
-- player1 holds EXACTLY one FIRE_SWORD craft worth -> concurrency test anchor.
INSERT INTO player_inventory (player_id, item_code, qty, updated_at) VALUES
 (2, 'MAT_IRON',       3, TIMESTAMP '2026-09-01 00:00:00'),
 (2, 'MAT_MAGIC_CORE', 2, TIMESTAMP '2026-09-01 00:00:00'),
 (2, 'MAT_FIRE_SHARD', 1, TIMESTAMP '2026-09-01 00:00:00'),
 (3, 'MAT_WOOD',       5, TIMESTAMP '2026-09-01 00:00:00'),
 (3, 'MAT_MAGIC_CORE', 1, TIMESTAMP '2026-09-01 00:00:00'),
 (4, 'MAT_IRON',       9, TIMESTAMP '2026-09-01 00:00:00'),
 (4, 'MAT_MAGIC_CORE', 6, TIMESTAMP '2026-09-01 00:00:00'),
 (4, 'MAT_FIRE_SHARD', 3, TIMESTAMP '2026-09-01 00:00:00'),
 (4, 'MAT_WOOD',       4, TIMESTAMP '2026-09-01 00:00:00'),
 (4, 'GOLD',           500, TIMESTAMP '2026-09-01 00:00:00');
