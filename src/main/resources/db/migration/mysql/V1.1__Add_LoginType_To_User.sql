
# 0 -> every type accepted, 1 -> app auth default, 2 -> active directory
ALTER TABLE user ADD COLUMN login_type INT NOT NULL DEFAULT 0 AFTER updated_at;