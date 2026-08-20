DO $$
DECLARE
    collisions text;
BEGIN
    SELECT string_agg(format('%I', c.relname), ', ' ORDER BY c.relname)
    INTO collisions
    FROM pg_class c
    JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname = 'public'
      AND c.relname = ANY (ARRAY[
          'audit_events',
          'balance_view_events',
          'deletion_requests',
          'face_simulation_outputs',
          'media_blobs',
          'notification_attempts',
          'notification_preferences',
          'outbox_jobs',
          'plan_actions',
          'plan_progress',
          'protection_proposals',
          'web_push_subscriptions'
      ]::text[]);

    IF collisions IS NOT NULL THEN
        RAISE EXCEPTION 'V3 complete-domain table-name collision(s): %', collisions
            USING ERRCODE = '42P07';
    END IF;
END $$;

ALTER TABLE public.consents
    ADD COLUMN IF NOT EXISTS consent_version integer DEFAULT 1,
    ADD COLUMN IF NOT EXISTS text_hash text,
    ADD COLUMN IF NOT EXISTS version bigint DEFAULT 0,
    ADD COLUMN IF NOT EXISTS updated_at timestamp with time zone DEFAULT now();

UPDATE public.consents
SET consent_version = 1
WHERE consent_version IS NULL;

UPDATE public.consents
SET version = 0
WHERE version IS NULL;

UPDATE public.consents
SET updated_at = coalesce(granted_at, revoked_at, now())
WHERE updated_at IS NULL;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'consents_purpose_check'
          AND conrelid = 'public.consents'::regclass
    ) THEN
        ALTER TABLE public.consents
            DROP CONSTRAINT consents_purpose_check;
    END IF;
END $$;

UPDATE public.consents
SET purpose = CASE purpose
    WHEN 'health_data' THEN 'HEALTH_COLLECTION'
    WHEN 'meal_photo' THEN 'MEAL_AI'
    WHEN 'face_simulation' THEN 'FACE_AI'
    ELSE purpose
END
WHERE purpose IN ('health_data', 'meal_photo', 'face_simulation');

UPDATE public.consents
SET text_hash = 'legacy:' || encode(
        digest(id::text || ':' || purpose || ':' || granted::text, 'sha256'),
        'hex'
    )
WHERE text_hash IS NULL;

ALTER TABLE public.consents
    ALTER COLUMN consent_version SET NOT NULL,
    ALTER COLUMN text_hash SET NOT NULL,
    ALTER COLUMN version SET NOT NULL,
    ALTER COLUMN updated_at SET NOT NULL;

ALTER TABLE public.consents
    ADD CONSTRAINT consents_purpose_check CHECK (
        purpose IN (
            'HEALTH_COLLECTION',
            'MEAL_AI',
            'FACE_AI',
            'NOTIFICATION'
        )
    ),
    ADD CONSTRAINT consents_version_check CHECK (consent_version > 0),
    ADD CONSTRAINT consents_text_hash_check CHECK (length(btrim(text_hash)) > 0),
    ADD CONSTRAINT consents_lifecycle_check CHECK (
        NOT granted OR (granted_at IS NOT NULL AND revoked_at IS NULL)
    ) NOT VALID;

ALTER TABLE public.face_simulations
    ADD COLUMN IF NOT EXISTS source_media_id uuid,
    ADD COLUMN IF NOT EXISTS version bigint DEFAULT 0,
    ADD COLUMN IF NOT EXISTS updated_at timestamp with time zone DEFAULT now();

ALTER TABLE public.face_simulations
    ALTER COLUMN original_photo_url DROP NOT NULL;

UPDATE public.face_simulations
SET version = 0
WHERE version IS NULL;

UPDATE public.face_simulations
SET updated_at = created_at
WHERE updated_at IS NULL;

ALTER TABLE public.face_simulations
    ALTER COLUMN version SET NOT NULL,
    ALTER COLUMN updated_at SET NOT NULL;

ALTER TABLE public.meal_records
    ADD COLUMN IF NOT EXISTS media_blob_id uuid;

ALTER TABLE public.meal_records
    ALTER COLUMN photo_url DROP NOT NULL;

ALTER TABLE public.plans
    ADD COLUMN IF NOT EXISTS version bigint DEFAULT 0,
    ADD COLUMN IF NOT EXISTS updated_at timestamp with time zone DEFAULT now();

UPDATE public.plans
SET version = 0
WHERE version IS NULL;

UPDATE public.plans
SET updated_at = coalesce(responded_at, created_at)
WHERE updated_at IS NULL;

ALTER TABLE public.plans
    ALTER COLUMN version SET NOT NULL,
    ALTER COLUMN updated_at SET NOT NULL;

ALTER TABLE public.protection_events
    ADD COLUMN IF NOT EXISTS idempotency_key text,
    ADD COLUMN IF NOT EXISTS version bigint DEFAULT 0,
    ADD COLUMN IF NOT EXISTS updated_at timestamp with time zone;

UPDATE public.protection_events
SET idempotency_key = 'legacy:' || id::text
WHERE idempotency_key IS NULL;

UPDATE public.protection_events
SET version = 0
WHERE version IS NULL;

UPDATE public.protection_events
SET updated_at = created_at
WHERE updated_at IS NULL;

ALTER TABLE public.protection_events
    ALTER COLUMN idempotency_key SET NOT NULL,
    ALTER COLUMN version SET NOT NULL,
    ALTER COLUMN updated_at SET DEFAULT now(),
    ALTER COLUMN updated_at SET NOT NULL,
    ADD CONSTRAINT protection_events_idempotency_key_check
        CHECK (length(btrim(idempotency_key)) > 0),
    ADD CONSTRAINT uk_protection_events_user_idempotency
        UNIQUE (user_id, idempotency_key);

CREATE TABLE public.audit_events (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    user_id uuid NOT NULL,
    event_type text NOT NULL,
    subject_type text NOT NULL,
    subject_id uuid,
    detail_json jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT audit_events_pkey PRIMARY KEY (id),
    CONSTRAINT audit_events_user_id_fkey
        FOREIGN KEY (user_id) REFERENCES public.users(id),
    CONSTRAINT audit_events_event_type_check CHECK (length(btrim(event_type)) > 0),
    CONSTRAINT audit_events_subject_type_check CHECK (length(btrim(subject_type)) > 0),
    CONSTRAINT audit_events_detail_json_check CHECK (jsonb_typeof(detail_json) = 'object')
);

CREATE TABLE public.media_blobs (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    user_id uuid NOT NULL,
    purpose text NOT NULL,
    status text DEFAULT 'active' NOT NULL,
    content_type text NOT NULL,
    size_bytes bigint NOT NULL,
    sha256 text NOT NULL,
    content bytea NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    deleted_at timestamp with time zone,
    CONSTRAINT media_blobs_pkey PRIMARY KEY (id),
    CONSTRAINT media_blobs_user_id_fkey
        FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE,
    CONSTRAINT uk_media_blobs_user_id_id UNIQUE (user_id, id),
    CONSTRAINT media_blobs_purpose_check CHECK (
        purpose IN ('meal_input', 'face_input', 'face_output_current', 'face_output_improved')
    ),
    CONSTRAINT media_blobs_status_check CHECK (
        status IN ('active', 'pending_delete', 'deleted')
    ),
    CONSTRAINT media_blobs_content_type_check CHECK (
        content_type IN ('image/jpeg', 'image/png', 'image/webp')
    ),
    CONSTRAINT media_blobs_size_check CHECK (
        size_bytes > 0
        AND size_bytes <= 20971520
        AND size_bytes = octet_length(content)::bigint
    ),
    CONSTRAINT media_blobs_sha256_check CHECK (length(btrim(sha256)) >= 32),
    CONSTRAINT media_blobs_deleted_state_check CHECK (
        (status = 'deleted' AND deleted_at IS NOT NULL)
        OR (status <> 'deleted' AND deleted_at IS NULL)
    ),
    CONSTRAINT uk_media_blobs_user_purpose_sha256 UNIQUE (user_id, purpose, sha256)
);

ALTER TABLE public.face_simulations
    ADD CONSTRAINT uk_face_simulations_user_id_id UNIQUE (user_id, id);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'face_simulations_source_media_id_fkey'
          AND conrelid = 'public.face_simulations'::regclass
    ) THEN
        ALTER TABLE public.face_simulations
            ADD CONSTRAINT face_simulations_source_media_id_fkey
            FOREIGN KEY (user_id, source_media_id)
            REFERENCES public.media_blobs(user_id, id);
    END IF;

END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'meal_records_media_blob_id_fkey'
          AND conrelid = 'public.meal_records'::regclass
    ) THEN
        ALTER TABLE public.meal_records
            ADD CONSTRAINT meal_records_media_blob_id_fkey
            FOREIGN KEY (user_id, media_blob_id)
            REFERENCES public.media_blobs(user_id, id);
    END IF;

END $$;

CREATE TABLE public.web_push_subscriptions (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    user_id uuid NOT NULL,
    endpoint_hash text NOT NULL,
    endpoint_ciphertext text NOT NULL,
    p256dh_ciphertext text NOT NULL,
    auth_ciphertext text NOT NULL,
    expiration_time timestamp with time zone,
    active boolean DEFAULT true NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT web_push_subscriptions_pkey PRIMARY KEY (id),
    CONSTRAINT web_push_subscriptions_user_id_fkey
        FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE,
    CONSTRAINT uk_web_push_subscriptions_user_id_id UNIQUE (user_id, id),
    CONSTRAINT web_push_subscriptions_endpoint_hash_key UNIQUE (endpoint_hash),
    CONSTRAINT web_push_subscriptions_endpoint_hash_check CHECK (length(btrim(endpoint_hash)) >= 32),
    CONSTRAINT web_push_subscriptions_ciphertext_check CHECK (
        length(btrim(endpoint_ciphertext)) > 0
        AND length(btrim(p256dh_ciphertext)) > 0
        AND length(btrim(auth_ciphertext)) > 0
    )
);

CREATE TABLE public.notification_preferences (
    user_id uuid NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    timezone text DEFAULT 'Asia/Seoul' NOT NULL,
    morning_time time without time zone DEFAULT '08:00:00' NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT notification_preferences_pkey PRIMARY KEY (user_id),
    CONSTRAINT notification_preferences_user_id_fkey
        FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE,
    CONSTRAINT notification_preferences_timezone_check CHECK (length(btrim(timezone)) > 0)
);

CREATE TABLE public.notification_attempts (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    user_id uuid NOT NULL,
    subscription_id uuid NOT NULL,
    local_date date NOT NULL,
    type text NOT NULL,
    status text DEFAULT 'pending' NOT NULL,
    attempt_count integer DEFAULT 0 NOT NULL,
    next_attempt_at timestamp with time zone,
    version bigint DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT notification_attempts_pkey PRIMARY KEY (id),
    CONSTRAINT notification_attempts_user_id_fkey
        FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE,
    CONSTRAINT notification_attempts_subscription_id_fkey
        FOREIGN KEY (user_id, subscription_id)
        REFERENCES public.web_push_subscriptions(user_id, id) ON DELETE CASCADE,
    CONSTRAINT notification_attempts_type_check CHECK (
        type IN ('morning_statement', 'plan_reminder', 'protection_alert')
    ),
    CONSTRAINT notification_attempts_status_check CHECK (
        status IN ('pending', 'processing', 'sent', 'retry', 'failed', 'cancelled')
    ),
    CONSTRAINT notification_attempts_attempt_count_check CHECK (attempt_count >= 0),
    CONSTRAINT uk_notification_attempts_subscription_date_type
        UNIQUE (subscription_id, local_date, type)
);

CREATE TABLE public.outbox_jobs (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    user_id uuid,
    job_type text NOT NULL,
    status text DEFAULT 'pending' NOT NULL,
    idempotency_key text NOT NULL,
    payload_json jsonb DEFAULT '{}'::jsonb NOT NULL,
    attempt_count integer DEFAULT 0 NOT NULL,
    next_attempt_at timestamp with time zone,
    version bigint DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT outbox_jobs_pkey PRIMARY KEY (id),
    CONSTRAINT outbox_jobs_user_id_fkey
        FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE SET NULL,
    CONSTRAINT outbox_jobs_status_check CHECK (
        status IN ('pending', 'processing', 'completed', 'retry', 'failed', 'cancelled')
    ),
    CONSTRAINT outbox_jobs_attempt_count_check CHECK (attempt_count >= 0),
    CONSTRAINT outbox_jobs_job_type_check CHECK (length(btrim(job_type)) > 0),
    CONSTRAINT outbox_jobs_idempotency_key_check CHECK (length(btrim(idempotency_key)) > 0),
    CONSTRAINT outbox_jobs_payload_json_check CHECK (jsonb_typeof(payload_json) = 'object')
);

CREATE TABLE public.plan_actions (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    plan_id uuid NOT NULL,
    position integer NOT NULL,
    action_type text NOT NULL,
    target_minutes integer NOT NULL,
    source_id uuid,
    rule_id uuid,
    version bigint DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT plan_actions_pkey PRIMARY KEY (id),
    CONSTRAINT plan_actions_plan_id_fkey
        FOREIGN KEY (plan_id) REFERENCES public.plans(id) ON DELETE CASCADE,
    CONSTRAINT plan_actions_source_id_fkey
        FOREIGN KEY (source_id) REFERENCES public.sources(id),
    CONSTRAINT plan_actions_rule_id_fkey
        FOREIGN KEY (rule_id) REFERENCES public.conversion_rules(id),
    CONSTRAINT plan_actions_position_check CHECK (position >= 0),
    CONSTRAINT plan_actions_action_type_check CHECK (length(btrim(action_type)) > 0),
    CONSTRAINT plan_actions_target_minutes_check CHECK (target_minutes > 0),
    CONSTRAINT uk_plan_actions_plan_position UNIQUE (plan_id, position)
);

CREATE TABLE public.plan_progress (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    plan_id uuid NOT NULL,
    progress_date date NOT NULL,
    completed_minutes integer DEFAULT 0 NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT plan_progress_pkey PRIMARY KEY (id),
    CONSTRAINT plan_progress_plan_id_fkey
        FOREIGN KEY (plan_id) REFERENCES public.plans(id) ON DELETE CASCADE,
    CONSTRAINT plan_progress_completed_minutes_check CHECK (completed_minutes >= 0),
    CONSTRAINT uk_plan_progress_plan_date UNIQUE (plan_id, progress_date)
);

CREATE TABLE public.protection_proposals (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    user_id uuid NOT NULL,
    status text DEFAULT 'proposed' NOT NULL,
    idempotency_key text NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    responded_at timestamp with time zone,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT protection_proposals_pkey PRIMARY KEY (id),
    CONSTRAINT protection_proposals_user_id_fkey
        FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE,
    CONSTRAINT protection_proposals_status_check CHECK (
        status IN ('proposed', 'accepted', 'declined', 'expired', 'cancelled')
    ),
    CONSTRAINT protection_proposals_response_check CHECK (
        (status = 'proposed' AND responded_at IS NULL)
        OR (status <> 'proposed' AND responded_at IS NOT NULL)
    ),
    CONSTRAINT uk_protection_proposals_user_idempotency UNIQUE (user_id, idempotency_key)
);

CREATE TABLE public.balance_view_events (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    user_id uuid NOT NULL,
    balance_minutes bigint NOT NULL,
    idempotency_key text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT balance_view_events_pkey PRIMARY KEY (id),
    CONSTRAINT balance_view_events_user_id_fkey
        FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE,
    CONSTRAINT uk_balance_view_events_user_idempotency UNIQUE (user_id, idempotency_key)
);

CREATE TABLE public.face_simulation_outputs (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    user_id uuid NOT NULL,
    simulation_id uuid NOT NULL,
    media_blob_id uuid NOT NULL,
    label text NOT NULL,
    model_version text NOT NULL,
    prompt_version text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT face_simulation_outputs_pkey PRIMARY KEY (id),
    CONSTRAINT face_simulation_outputs_user_id_fkey
        FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE,
    CONSTRAINT face_simulation_outputs_simulation_id_fkey
        FOREIGN KEY (user_id, simulation_id)
        REFERENCES public.face_simulations(user_id, id) ON DELETE CASCADE,
    CONSTRAINT face_simulation_outputs_media_blob_id_fkey
        FOREIGN KEY (user_id, media_blob_id)
        REFERENCES public.media_blobs(user_id, id),
    CONSTRAINT face_simulation_outputs_label_check CHECK (label IN ('current', 'improved')),
    CONSTRAINT uk_face_simulation_outputs_simulation_label UNIQUE (simulation_id, label),
    CONSTRAINT face_simulation_outputs_media_blob_id_key UNIQUE (media_blob_id)
);

CREATE TABLE public.deletion_requests (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    user_id uuid NOT NULL,
    scope text NOT NULL,
    status text DEFAULT 'requested' NOT NULL,
    idempotency_key text NOT NULL,
    attempt_count integer DEFAULT 0 NOT NULL,
    requested_at timestamp with time zone DEFAULT now() NOT NULL,
    completed_at timestamp with time zone,
    version bigint DEFAULT 0 NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT deletion_requests_pkey PRIMARY KEY (id),
    CONSTRAINT deletion_requests_user_id_fkey
        FOREIGN KEY (user_id) REFERENCES public.users(id),
    CONSTRAINT deletion_requests_scope_check CHECK (
        scope IN ('health', 'meal', 'face', 'notification', 'account')
    ),
    CONSTRAINT deletion_requests_status_check CHECK (
        status IN ('requested', 'processing', 'retry', 'completed', 'failed', 'cancelled')
    ),
    CONSTRAINT deletion_requests_attempt_count_check CHECK (attempt_count >= 0),
    CONSTRAINT deletion_requests_completion_check CHECK (
        (status = 'completed' AND completed_at IS NOT NULL)
        OR (status <> 'completed')
    ),
    CONSTRAINT uk_deletion_requests_user_idempotency UNIQUE (user_id, idempotency_key)
);

CREATE INDEX idx_audit_events_user_created
    ON public.audit_events (user_id, created_at DESC);

CREATE INDEX idx_media_blobs_user_status
    ON public.media_blobs (user_id, status, created_at DESC);

CREATE INDEX idx_meal_records_media_blob_id
    ON public.meal_records (media_blob_id);

CREATE INDEX idx_face_simulations_source_media_id
    ON public.face_simulations (source_media_id);

CREATE INDEX idx_web_push_subscriptions_active_user
    ON public.web_push_subscriptions (user_id, updated_at DESC)
    WHERE active;

CREATE INDEX idx_notification_attempts_due
    ON public.notification_attempts (status, next_attempt_at)
    WHERE status IN ('pending', 'retry');

CREATE INDEX idx_outbox_jobs_due
    ON public.outbox_jobs (status, next_attempt_at)
    WHERE status IN ('pending', 'retry');

CREATE UNIQUE INDEX uk_outbox_jobs_user_type_idempotency
    ON public.outbox_jobs (user_id, job_type, idempotency_key)
    WHERE user_id IS NOT NULL;

CREATE UNIQUE INDEX uk_outbox_jobs_type_idempotency
    ON public.outbox_jobs (job_type, idempotency_key)
    WHERE user_id IS NULL;

CREATE INDEX idx_plan_progress_plan_date
    ON public.plan_progress (plan_id, progress_date DESC);

CREATE INDEX idx_protection_proposals_user_status
    ON public.protection_proposals (user_id, status, created_at DESC);

CREATE INDEX idx_protection_events_user_created
    ON public.protection_events (user_id, created_at DESC);

CREATE INDEX idx_balance_view_events_user_created
    ON public.balance_view_events (user_id, created_at DESC);

CREATE INDEX idx_face_simulation_outputs_simulation
    ON public.face_simulation_outputs (simulation_id);

CREATE INDEX idx_deletion_requests_status_updated
    ON public.deletion_requests (status, updated_at)
    WHERE status IN ('requested', 'processing', 'retry');

CREATE OR REPLACE FUNCTION public.enforce_meal_media_transition() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.media_blob_id IS NULL OR NEW.photo_url IS NOT NULL THEN
            RAISE EXCEPTION 'new meal_records rows require media_blob_id and null photo_url'
                USING ERRCODE = '23514';
        END IF;
    ELSE
        IF NEW.user_id IS DISTINCT FROM OLD.user_id THEN
            RAISE EXCEPTION 'meal_records user_id is immutable'
                USING ERRCODE = '23514';
        END IF;
        IF NEW.photo_url IS DISTINCT FROM OLD.photo_url THEN
            RAISE EXCEPTION 'legacy meal_records photo_url is read-only'
                USING ERRCODE = '23514';
        END IF;
        IF OLD.media_blob_id IS NOT NULL AND NEW.media_blob_id IS NULL THEN
            RAISE EXCEPTION 'meal_records media_blob_id cannot be cleared'
                USING ERRCODE = '23514';
        END IF;
    END IF;
    IF NEW.media_blob_id IS NOT NULL AND NOT EXISTS (
        SELECT 1
        FROM public.media_blobs
        WHERE id = NEW.media_blob_id
          AND user_id = NEW.user_id
          AND purpose = 'meal_input'
    ) THEN
        RAISE EXCEPTION 'meal_records media_blob_id must reference owned meal_input media'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER trg_meal_records_enforce_media_storage
    BEFORE INSERT OR UPDATE ON public.meal_records
    FOR EACH ROW EXECUTE FUNCTION public.enforce_meal_media_transition();

CREATE OR REPLACE FUNCTION public.enforce_face_media_transition() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.source_media_id IS NULL
            OR NEW.original_photo_url IS NOT NULL
            OR NEW.result_current_url IS NOT NULL
            OR NEW.result_improved_url IS NOT NULL THEN
            RAISE EXCEPTION 'new face_simulations rows require source_media_id and null legacy URLs'
                USING ERRCODE = '23514';
        END IF;
    ELSE
        IF NEW.user_id IS DISTINCT FROM OLD.user_id THEN
            RAISE EXCEPTION 'face_simulations user_id is immutable'
                USING ERRCODE = '23514';
        END IF;
        IF NEW.original_photo_url IS DISTINCT FROM OLD.original_photo_url
            OR NEW.result_current_url IS DISTINCT FROM OLD.result_current_url
            OR NEW.result_improved_url IS DISTINCT FROM OLD.result_improved_url THEN
            RAISE EXCEPTION 'legacy face_simulations URL columns are read-only'
                USING ERRCODE = '23514';
        END IF;
        IF OLD.source_media_id IS NOT NULL AND NEW.source_media_id IS NULL THEN
            RAISE EXCEPTION 'face_simulations source_media_id cannot be cleared'
                USING ERRCODE = '23514';
        END IF;
    END IF;
    IF NEW.source_media_id IS NOT NULL AND NOT EXISTS (
        SELECT 1
        FROM public.media_blobs
        WHERE id = NEW.source_media_id
          AND user_id = NEW.user_id
          AND purpose = 'face_input'
    ) THEN
        RAISE EXCEPTION 'face_simulations source_media_id must reference owned face_input media'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER trg_face_simulations_enforce_media_storage
    BEFORE INSERT OR UPDATE ON public.face_simulations
    FOR EACH ROW EXECUTE FUNCTION public.enforce_face_media_transition();

CREATE OR REPLACE FUNCTION public.enforce_face_output_media_purpose() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    required_purpose text;
BEGIN
    required_purpose := CASE NEW.label
        WHEN 'current' THEN 'face_output_current'
        WHEN 'improved' THEN 'face_output_improved'
        ELSE NULL
    END;

    IF required_purpose IS NULL OR NOT EXISTS (
        SELECT 1
        FROM public.media_blobs
        WHERE id = NEW.media_blob_id
          AND user_id = NEW.user_id
          AND purpose = required_purpose
    ) THEN
        RAISE EXCEPTION 'face_simulation_outputs media purpose must match label %', NEW.label
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER trg_face_simulation_outputs_enforce_media_purpose
    BEFORE INSERT OR UPDATE ON public.face_simulation_outputs
    FOR EACH ROW EXECUTE FUNCTION public.enforce_face_output_media_purpose();

CREATE OR REPLACE FUNCTION public.forbid_media_identity_mutation() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NEW.user_id IS DISTINCT FROM OLD.user_id
        OR NEW.purpose IS DISTINCT FROM OLD.purpose THEN
        RAISE EXCEPTION 'media_blobs user_id and purpose are immutable'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER trg_media_blobs_identity_immutable
    BEFORE UPDATE OF user_id, purpose ON public.media_blobs
    FOR EACH ROW EXECUTE FUNCTION public.forbid_media_identity_mutation();

CREATE OR REPLACE FUNCTION public.forbid_audit_event_mutation() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    RAISE EXCEPTION 'audit_events is immutable';
END $$;

CREATE TRIGGER trg_audit_events_immutable
    BEFORE UPDATE OR DELETE ON public.audit_events
    FOR EACH ROW EXECUTE FUNCTION public.forbid_audit_event_mutation();

CREATE OR REPLACE FUNCTION public.forbid_protection_event_mutation() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    RAISE EXCEPTION 'protection_events is immutable';
END $$;

CREATE TRIGGER trg_protection_events_immutable
    BEFORE UPDATE OR DELETE ON public.protection_events
    FOR EACH ROW EXECUTE FUNCTION public.forbid_protection_event_mutation();

CREATE OR REPLACE FUNCTION public.touch_updated_at() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END $$;

CREATE TRIGGER trg_consents_touch_updated_at
    BEFORE UPDATE ON public.consents
    FOR EACH ROW EXECUTE FUNCTION public.touch_updated_at();

CREATE TRIGGER trg_face_simulations_touch_updated_at
    BEFORE UPDATE ON public.face_simulations
    FOR EACH ROW EXECUTE FUNCTION public.touch_updated_at();

CREATE TRIGGER trg_plans_touch_updated_at
    BEFORE UPDATE ON public.plans
    FOR EACH ROW EXECUTE FUNCTION public.touch_updated_at();

CREATE TRIGGER trg_media_blobs_touch_updated_at
    BEFORE UPDATE ON public.media_blobs
    FOR EACH ROW EXECUTE FUNCTION public.touch_updated_at();

CREATE TRIGGER trg_web_push_subscriptions_touch_updated_at
    BEFORE UPDATE ON public.web_push_subscriptions
    FOR EACH ROW EXECUTE FUNCTION public.touch_updated_at();

CREATE TRIGGER trg_notification_preferences_touch_updated_at
    BEFORE UPDATE ON public.notification_preferences
    FOR EACH ROW EXECUTE FUNCTION public.touch_updated_at();

CREATE TRIGGER trg_notification_attempts_touch_updated_at
    BEFORE UPDATE ON public.notification_attempts
    FOR EACH ROW EXECUTE FUNCTION public.touch_updated_at();

CREATE TRIGGER trg_outbox_jobs_touch_updated_at
    BEFORE UPDATE ON public.outbox_jobs
    FOR EACH ROW EXECUTE FUNCTION public.touch_updated_at();

CREATE TRIGGER trg_plan_actions_touch_updated_at
    BEFORE UPDATE ON public.plan_actions
    FOR EACH ROW EXECUTE FUNCTION public.touch_updated_at();

CREATE TRIGGER trg_plan_progress_touch_updated_at
    BEFORE UPDATE ON public.plan_progress
    FOR EACH ROW EXECUTE FUNCTION public.touch_updated_at();

CREATE TRIGGER trg_protection_proposals_touch_updated_at
    BEFORE UPDATE ON public.protection_proposals
    FOR EACH ROW EXECUTE FUNCTION public.touch_updated_at();

CREATE TRIGGER trg_deletion_requests_touch_updated_at
    BEFORE UPDATE ON public.deletion_requests
    FOR EACH ROW EXECUTE FUNCTION public.touch_updated_at();
