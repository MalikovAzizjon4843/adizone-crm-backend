-- Teacher <-> User bog'lanishi.
--
-- teachers.user_id ustuni entity da allaqachon bor edi, lekin @ManyToOne edi va
-- UNIQUE cheklovi yo'q edi: bitta userga bir nechta Teacher profili osilib qolishi
-- mumkin edi. Endi @OneToOne, indeks esa shu yerda ANIQ yaratiladi —
-- ddl-auto: update mavjud ustunga unique indeks qo'shilishini kafolatlamaydi.
--
-- Eslatma: V41 raqami V41__notice_reads.sql tomonidan band, shuning uchun V50.

ALTER TABLE teachers
    ADD COLUMN IF NOT EXISTS user_id BIGINT;

-- FK bo'lmasa qo'shamiz (nomini aniq beramiz — ddl-auto tasodifiy nom qo'yadi).
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fk_teachers_user'
    ) THEN
        ALTER TABLE teachers
            ADD CONSTRAINT fk_teachers_user
            FOREIGN KEY (user_id) REFERENCES users(id);
    END IF;
END $$;

-- UNIQUE indeksdan oldin dublikatlarni tekshiramiz. Ularni avtomatik "tuzatish"
-- xavfli (qaysi profilni uzish kerakligini faqat admin biladi), shuning uchun
-- migratsiya aniq xabar bilan to'xtaydi.
DO $$
DECLARE
    dup TEXT;
BEGIN
    SELECT string_agg(user_id::TEXT, ', ')
      INTO dup
      FROM (
          SELECT user_id
            FROM teachers
           WHERE user_id IS NOT NULL
           GROUP BY user_id
          HAVING COUNT(*) > 1
      ) d;

    IF dup IS NOT NULL THEN
        RAISE EXCEPTION
            'teachers.user_id dublikatlari bor (user_id: %). Ortiqcha profillarda user_id ni NULL qiling yoki ularni birlashtiring, keyin migratsiyani qayta ishga tushiring.',
            dup;
    END IF;
END $$;

CREATE UNIQUE INDEX IF NOT EXISTS ux_teachers_user_id
    ON teachers (user_id)
    WHERE user_id IS NOT NULL;

-- Sinxronda telefon bo'yicha "egasiz" profil qidiriladi.
CREATE INDEX IF NOT EXISTS idx_teachers_phone ON teachers (phone);
