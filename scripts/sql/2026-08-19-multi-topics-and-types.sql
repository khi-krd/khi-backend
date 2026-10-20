-- Multi-topics & multi-types backfill
--
-- Run AFTER the backend has started once with ddl-auto=update so the new
-- tables exist:
--   sound_track_topics, video_topics, writing_topics,
--   image_collection_topics     (topic many-to-many joins)
--   sound_track_sound_types     (multi sound types)
--   contact_office_types        (multi office types)
--   project_type_selections     (multi bilingual project types)
--
-- Each INSERT ... SELECT copies the legacy singular value into the new
-- collection table. WHERE NOT EXISTS guards make the script re-runnable
-- regardless of the constraints Hibernate generated.

BEGIN;

-- ─── Topics ─────────────────────────────────────────────────────────────

INSERT INTO sound_track_topics (sound_track_id, topic_id)
SELECT s.id, s.topic_id FROM sound_tracks s
WHERE s.topic_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM sound_track_topics t
      WHERE t.sound_track_id = s.id AND t.topic_id = s.topic_id);

INSERT INTO video_topics (video_id, topic_id)
SELECT v.id, v.topic_id FROM videos v
WHERE v.topic_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM video_topics t
      WHERE t.video_id = v.id AND t.topic_id = v.topic_id);

INSERT INTO writing_topics (writing_id, topic_id)
SELECT w.id, w.topic_id FROM writings w
WHERE w.topic_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM writing_topics t
      WHERE t.writing_id = w.id AND t.topic_id = w.topic_id);

INSERT INTO image_collection_topics (collection_id, topic_id)
SELECT c.id, c.topic_id FROM image_collections c
WHERE c.topic_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM image_collection_topics t
      WHERE t.collection_id = c.id AND t.topic_id = c.topic_id);

-- ─── Types ──────────────────────────────────────────────────────────────

INSERT INTO sound_track_sound_types (sound_track_id, sound_type)
SELECT s.id, s.sound_type FROM sound_tracks s
WHERE s.sound_type IS NOT NULL AND btrim(s.sound_type) <> ''
  AND NOT EXISTS (
      SELECT 1 FROM sound_track_sound_types t
      WHERE t.sound_track_id = s.id AND t.sound_type = s.sound_type);

INSERT INTO contact_office_types (contact_id, office_type)
SELECT c.id, c.office_type FROM contact_pages c
WHERE c.office_type IS NOT NULL AND btrim(c.office_type) <> ''
  AND NOT EXISTS (
      SELECT 1 FROM contact_office_types t
      WHERE t.contact_id = c.id AND t.office_type = c.office_type);

INSERT INTO project_type_selections (project_id, type_ckb, type_kmr)
SELECT p.id, p.project_type_ckb,
       COALESCE(NULLIF(btrim(p.project_type_kmr), ''), p.project_type_ckb)
FROM projects p
WHERE p.project_type_ckb IS NOT NULL AND btrim(p.project_type_ckb) <> ''
  AND NOT EXISTS (
      SELECT 1 FROM project_type_selections t
      WHERE t.project_id = p.id AND t.type_ckb = p.project_type_ckb);

COMMIT;

-- NOTE: the legacy columns (topic_id, sound_type, office_type,
-- project_type_ckb/kmr) are intentionally kept — they carry the primary
-- selection for backward compatibility and are kept in sync on write.
