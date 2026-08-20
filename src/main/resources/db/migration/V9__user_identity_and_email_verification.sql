ALTER TABLE public.users
    ADD COLUMN name text,
    ADD COLUMN email_verified boolean NOT NULL DEFAULT false,
    ADD COLUMN email_verification_code text,
    ADD COLUMN email_verification_expires_at timestamptz;

-- 기존 계정은 소급 인증 처리하고, 이름은 닉네임(없으면 이메일 로컬파트)으로 백필한다.
UPDATE public.users
SET email_verified = true,
    name = COALESCE(NULLIF(btrim(nickname), ''), split_part(email, '@', 1));
