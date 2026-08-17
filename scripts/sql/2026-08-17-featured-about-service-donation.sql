-- ============================================================================
-- Featured for About / Service / Donation — schema repair
--
-- WHY THIS FILE EXISTS
--   The app runs with ddl-auto: update, so these columns are normally added by
--   Hibernate at startup. On the Railway deployment that did not fully happen:
--
--     services            columns present, but NULL in every existing row
--                         -> Hibernate cannot map NULL into a primitive boolean,
--                            so every read of the table returned HTTP 500
--     about_pages         columns absent entirely -> every read returned 500
--     donation_settings   same symptom as about_pages
--
--   This script is idempotent: it adds what is missing, backfills NULLs, then
--   locks the booleans down as NOT NULL DEFAULT false so the mapping error
--   cannot come back. Safe to run more than once, safe to run before or after
--   deploying the fixed build.
--
-- HOW TO RUN
--   Railway dashboard -> Postgres service -> "Data" / "Query" tab -> paste, run.
--   Or:  psql "$DATABASE_URL" -f scripts/sql/2026-08-17-featured-about-service-donation.sql
--
-- NOTE
--   Running this alone is not enough. The homepage carousel (/featured) also
--   needed a query fix (SELECT DISTINCT + ORDER BY COALESCE is invalid on
--   PostgreSQL) — that requires redeploying the backend.
-- ============================================================================

BEGIN;

ALTER TABLE about_pages ADD COLUMN IF NOT EXISTS featured          boolean;
ALTER TABLE about_pages ADD COLUMN IF NOT EXISTS featured_order    integer;
ALTER TABLE about_pages ADD COLUMN IF NOT EXISTS feature_image_url text;

UPDATE about_pages SET featured = false WHERE featured IS NULL;

ALTER TABLE about_pages ALTER COLUMN featured SET DEFAULT false;
ALTER TABLE about_pages ALTER COLUMN featured SET NOT NULL;

ALTER TABLE services ADD COLUMN IF NOT EXISTS featured          boolean;
ALTER TABLE services ADD COLUMN IF NOT EXISTS featured_order    integer;
ALTER TABLE services ADD COLUMN IF NOT EXISTS feature_image_url text;

UPDATE services SET featured = false WHERE featured IS NULL;

ALTER TABLE services ALTER COLUMN featured SET DEFAULT false;
ALTER TABLE services ALTER COLUMN featured SET NOT NULL;

ALTER TABLE service_contents
    ADD COLUMN IF NOT EXISTS feature_description varchar(1000);


ALTER TABLE donation_settings ADD COLUMN IF NOT EXISTS featured          boolean;
ALTER TABLE donation_settings ADD COLUMN IF NOT EXISTS featured_order    integer;
ALTER TABLE donation_settings ADD COLUMN IF NOT EXISTS feature_image_url text;

UPDATE donation_settings SET featured = false WHERE featured IS NULL;

ALTER TABLE donation_settings ALTER COLUMN featured SET DEFAULT false;
ALTER TABLE donation_settings ALTER COLUMN featured SET NOT NULL;

COMMIT;

SELECT table_name, column_name, data_type, is_nullable, column_default
FROM information_schema.columns
WHERE (table_name = 'about_pages'       AND column_name IN ('featured','featured_order','feature_image_url'))
   OR (table_name = 'services'          AND column_name IN ('featured','featured_order','feature_image_url'))
   OR (table_name = 'donation_settings' AND column_name IN ('featured','featured_order','feature_image_url'))
   OR (table_name = 'service_contents'  AND column_name  = 'feature_description')
ORDER BY table_name, column_name;
