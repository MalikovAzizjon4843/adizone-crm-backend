-- FORMING status added to GroupStatus enum.
-- groups.status is stored as VARCHAR (EnumType.STRING),
-- so no schema change required. Allowed values:
--   FORMING   — Yig'ilayotgan
--   ACTIVE    — Faol
--   COMPLETED — Tugagan
--   CANCELLED — Bekor qilingan
SELECT 1;
