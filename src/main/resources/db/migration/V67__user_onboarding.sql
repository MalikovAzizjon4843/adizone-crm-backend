-- Xodimning onboarding holati (CRM frontend turlari): GET/PUT/DELETE /api/users/me/onboarding.
--
-- V66 dan KEYIN, QO'LDA, crm_user bilan bajariladi (deploy-v2.md §3.3). Idempotent: qayta bajarilsa hech
-- narsa o'zgarmaydi. ddl-auto: update jadvalni o'zi ham yaratadi — bu skript FK (ON DELETE CASCADE) va
-- kalit formati CHECK ini qo'shadi (Hibernate bularni bermaydi).

BEGIN;

CREATE TABLE IF NOT EXISTS user_onboarding (
    user_id   BIGINT       NOT NULL,
    tour_key  VARCHAR(80)  NOT NULL,
    seen_at   TIMESTAMP    NOT NULL,
    CONSTRAINT pk_user_onboarding PRIMARY KEY (user_id, tour_key)
);

DO $$
BEGIN
    -- FK ustuni bo'yicha tekshiriladi (Hibernate nomi bilan yaratilgan bo'lsa takrorlanmaydi)
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint c
          JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = ANY (c.conkey)
         WHERE c.conrelid = 'user_onboarding'::regclass AND c.contype = 'f'
           AND c.confrelid = 'users'::regclass AND a.attname = 'user_id'
    ) THEN
        ALTER TABLE user_onboarding ADD CONSTRAINT fk_user_onboarding_user
            FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;
    END IF;

    -- Kalit formati — UserOnboardingService.KEY_PATTERN bilan bir xil
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                    WHERE conrelid = 'user_onboarding'::regclass AND conname = 'ck_user_onboarding_key') THEN
        ALTER TABLE user_onboarding ADD CONSTRAINT ck_user_onboarding_key
            CHECK (tour_key ~ '^[a-z0-9]+([._-][a-z0-9]+)*$');
    END IF;
END $$;

COMMIT;
