ALTER TABLE public.users
    ADD COLUMN IF NOT EXISTS role text NOT NULL DEFAULT 'USER';

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'users_role_check'
          AND conrelid = 'public.users'::regclass
    ) THEN
        ALTER TABLE public.users
            ADD CONSTRAINT users_role_check CHECK (role IN ('USER', 'ADMIN'));
    END IF;
END $$;

ALTER TABLE public.refresh_tokens
    ADD COLUMN IF NOT EXISTS family_id uuid,
    ADD COLUMN IF NOT EXISTS previous_token_hash text,
    ADD COLUMN IF NOT EXISTS used_at timestamp with time zone,
    ADD COLUMN IF NOT EXISTS reuse_detected_at timestamp with time zone;

UPDATE public.refresh_tokens
SET family_id = id
WHERE family_id IS NULL;

ALTER TABLE public.refresh_tokens
    ALTER COLUMN family_id SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_refresh_token_hash
    ON public.refresh_tokens USING btree (token_hash);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'refresh_tokens_token_hash_key'
          AND conrelid = 'public.refresh_tokens'::regclass
    ) THEN
        ALTER TABLE public.refresh_tokens
            ADD CONSTRAINT refresh_tokens_token_hash_key UNIQUE (token_hash);
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_refresh_family
    ON public.refresh_tokens USING btree (family_id);

CREATE INDEX IF NOT EXISTS idx_refresh_active_user
    ON public.refresh_tokens USING btree (user_id, expires_at)
    WHERE revoked_at IS NULL;
