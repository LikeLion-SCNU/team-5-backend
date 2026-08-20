ALTER TABLE public.face_simulations
    ADD COLUMN IF NOT EXISTS idempotency_key text,
    ADD COLUMN IF NOT EXISTS request_hash text,
    ADD COLUMN IF NOT EXISTS failure_reason text,
    ADD COLUMN IF NOT EXISTS processing_started_at timestamp with time zone,
    ADD COLUMN IF NOT EXISTS completed_at timestamp with time zone,
    ADD COLUMN IF NOT EXISTS cancelled_at timestamp with time zone;

UPDATE public.face_simulations
SET idempotency_key = 'legacy:' || id::text
WHERE idempotency_key IS NULL;

UPDATE public.face_simulations
SET request_hash = 'legacy:' || id::text
WHERE request_hash IS NULL;

UPDATE public.face_simulations
SET completed_at = created_at
WHERE completed_at IS NULL
  AND status IN ('done', 'failed');

UPDATE public.face_simulations
SET processing_started_at = created_at
WHERE processing_started_at IS NULL
  AND status = 'generating';

ALTER TABLE public.face_simulations
    ALTER COLUMN idempotency_key SET NOT NULL,
    ALTER COLUMN request_hash SET NOT NULL;

ALTER TABLE public.face_simulations
    ADD CONSTRAINT face_simulations_idempotency_key_check
        CHECK (length(btrim(idempotency_key)) > 0),
    ADD CONSTRAINT face_simulations_request_hash_check
        CHECK (length(btrim(request_hash)) > 0),
    ADD CONSTRAINT uk_face_simulations_user_idempotency
        UNIQUE (user_id, idempotency_key);

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'face_simulations_status_check'
          AND conrelid = 'public.face_simulations'::regclass
    ) THEN
        ALTER TABLE public.face_simulations
            DROP CONSTRAINT face_simulations_status_check;
    END IF;
END $$;

ALTER TABLE public.face_simulations
    ADD CONSTRAINT face_simulations_status_check CHECK (
        status IN ('queued', 'processing', 'generating', 'done', 'failed', 'cancelled')
    ),
    ADD CONSTRAINT face_simulations_terminal_timestamp_check CHECK (
        (status IN ('done', 'failed') AND completed_at IS NOT NULL)
        OR (status NOT IN ('done', 'failed'))
    ),
    ADD CONSTRAINT face_simulations_cancelled_timestamp_check CHECK (
        (status = 'cancelled' AND cancelled_at IS NOT NULL)
        OR (status <> 'cancelled')
    );

CREATE INDEX idx_face_simulations_due
    ON public.face_simulations (status, created_at)
    WHERE status IN ('queued', 'generating');
