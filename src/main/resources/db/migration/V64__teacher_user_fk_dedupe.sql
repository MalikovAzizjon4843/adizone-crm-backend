-- teachers.user_id → users: dublikat FK larni bittaga keltirish (rehearsal REPORT O1, phase5-audit X-03).
--
-- Prod (repetitsiya, 2026-10-02) da shu ustunda ikkita FK bor:
--   fk_teachers_user       — V50, ON DELETE NO ACTION   → QOLADI
--   teachers_user_id_fkey  — eski skript, ON DELETE SET NULL → OLIB TASHLANADI
-- Ikkalasi birga bo'lsa amalda NO ACTION ishlaydi (SET NULL hech qachon bajarilmaydi) — ya'ni xulq
-- o'zgarmaydi, faqat chalg'ituvchi ikkinchi FK yo'qoladi. NO ACTION tanlangani: userlar soft-delete
-- qilinadi; profil bog'langan userni jismoniy o'chirish xato bilan to'xtashi kerak, profilni jimgina
-- uzib qo'yish emas (oylik TEACHER_PROFILE_MISSING ga tushardi).
--
-- V63 dan KEYIN, QO'LDA, crm_user bilan bajariladi (deploy-v2.md §3.3). Idempotent: FK lar NOMI
-- bo'yicha emas, USTUNI bo'yicha topiladi (lokal bazalarda Hibernate nomi fk… bilan NO ACTION FK ham
-- bo'lishi mumkin — u ham dublikat sifatida olib tashlanadi). Qayta ishga tushirilsa hech narsa
-- o'zgarmaydi. Natija: teachers.user_id da aynan bitta FK — fk_teachers_user, NO ACTION.
--
-- Hibernate (ddl-auto: update) qayta FK qo'shmaydi: shu ustun → users FK si bor bo'lsa, nomidan
-- qat'i nazar ekvivalent hisoblanadi.

BEGIN;

DO $$
DECLARE
    r record;
BEGIN
    -- 1) V50 FK si NO ACTION emas bo'lsa (kutilmagan holat) — qayta yaratiladi
    IF EXISTS (SELECT 1 FROM pg_constraint
                WHERE conrelid = 'teachers'::regclass AND conname = 'fk_teachers_user'
                  AND confdeltype <> 'a') THEN
        ALTER TABLE teachers DROP CONSTRAINT fk_teachers_user;
        RAISE NOTICE 'teachers: fk_teachers_user NO ACTION emas edi — qayta yaratiladi';
    END IF;

    -- 2) V50 FK si yo'q bo'lsa (V50 qo'llanmagan baza) — yaratiladi. Avval qo'shiladi, keyin
    --    qolganlari olib tashlanadi: oraliqda ustun FK siz qolmaydi.
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                    WHERE conrelid = 'teachers'::regclass AND conname = 'fk_teachers_user') THEN
        ALTER TABLE teachers
            ADD CONSTRAINT fk_teachers_user FOREIGN KEY (user_id) REFERENCES users(id);
        RAISE NOTICE 'teachers: fk_teachers_user (NO ACTION) yaratildi';
    END IF;

    -- 3) Shu ustundagi qolgan barcha FK lar (SET NULL va boshqa dublikatlar) olib tashlanadi
    FOR r IN
        SELECT c.conname, c.confdeltype
          FROM pg_constraint c
         WHERE c.conrelid = 'teachers'::regclass AND c.contype = 'f'
           AND c.confrelid = 'users'::regclass
           AND c.conname <> 'fk_teachers_user'
           AND c.conkey = ARRAY[(SELECT a.attnum FROM pg_attribute a
                                  WHERE a.attrelid = 'teachers'::regclass
                                    AND a.attname = 'user_id')]::int2[]
         ORDER BY c.conname
    LOOP
        EXECUTE format('ALTER TABLE teachers DROP CONSTRAINT %I', r.conname);
        RAISE NOTICE 'teachers: FK % [%] olib tashlandi', r.conname, r.confdeltype;
    END LOOP;
END $$;

COMMIT;
