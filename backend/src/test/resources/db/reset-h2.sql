-- Wipe all business data; schema (CREATE TABLE IF NOT EXISTS) is re-applied by
-- schema-h2.sql on context start, here we only clear rows for a reseed.
DELETE FROM craft_plan_repair;
DELETE FROM craft_plan_unit;
DELETE FROM craft_plan;
DELETE FROM idempotency_record;
DELETE FROM revoke_record;
DELETE FROM ledger_entry;
DELETE FROM material_hold;
DELETE FROM craft_order;
DELETE FROM player_inventory;
DELETE FROM recipe_version;
DELETE FROM recipe;
DELETE FROM login_token;
DELETE FROM app_user;
