-- 0002_no_r2_images.sql
--
-- Run this ONLY IF you already created the tables before reading the note
-- about R2 not being available. It is safe to run even if you don't need
-- it — every statement is guarded.
--
-- What this does: makes sure `products.image_data` exists (TEXT, holds a
-- base64 data URL like "data:image/png;base64,...."). If your existing
-- table has an `image_key` column from an earlier R2-based version, this
-- copies it over and you can ignore/leave the old column (D1/SQLite makes
-- dropping columns awkward; an unused leftover column is harmless).
--
-- How to run this from the Cloudflare dashboard (no CLI needed):
--   Workers & Pages -> D1 -> your database -> "Console" tab -> paste each
--   statement below one at a time (or all together) -> Execute.

ALTER TABLE products ADD COLUMN image_data TEXT;

-- If you previously had an image_key column (R2 object key) and want to
-- preserve it as a backup/reference column, this is harmless to leave in
-- place. We simply never read it anymore — routes/products.js only reads
-- and writes image_data going forward.
