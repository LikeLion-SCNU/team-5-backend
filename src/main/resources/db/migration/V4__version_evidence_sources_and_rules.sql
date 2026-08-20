ALTER TABLE public.sources
    ADD COLUMN logical_key uuid,
    ADD COLUMN version_number integer DEFAULT 1 NOT NULL,
    ADD COLUMN scope_ko text,
    ADD COLUMN row_version bigint DEFAULT 0 NOT NULL,
    ADD COLUMN updated_at timestamp with time zone DEFAULT now() NOT NULL;

UPDATE public.sources
SET logical_key = id
WHERE logical_key IS NULL;

ALTER TABLE public.sources
    ALTER COLUMN logical_key SET NOT NULL,
    ADD CONSTRAINT sources_version_number_check CHECK (version_number > 0),
    ADD CONSTRAINT uk_sources_logical_version UNIQUE (logical_key, version_number),
    ADD CONSTRAINT sources_doi_url_https_check
        CHECK (doi_url IS NULL OR doi_url ~ '^https://[^[:space:]]+$') NOT VALID;

CREATE INDEX idx_sources_active_title
    ON public.sources (is_active, title, version_number DESC);

ALTER TABLE public.conversion_rules
    ADD COLUMN logical_key uuid,
    ADD COLUMN version_number integer DEFAULT 1 NOT NULL,
    ADD COLUMN row_version bigint DEFAULT 0 NOT NULL,
    ADD COLUMN created_at timestamp with time zone DEFAULT now() NOT NULL,
    ADD COLUMN updated_at timestamp with time zone DEFAULT now() NOT NULL;

UPDATE public.conversion_rules
SET logical_key = id
WHERE logical_key IS NULL;

ALTER TABLE public.conversion_rules
    ALTER COLUMN logical_key SET NOT NULL,
    ADD CONSTRAINT conversion_rules_version_number_check CHECK (version_number > 0),
    ADD CONSTRAINT conversion_rules_minutes_delta_nonzero_check CHECK (minutes_delta <> 0) NOT VALID,
    ADD CONSTRAINT conversion_rules_unit_nonblank_check CHECK (length(btrim(unit)) > 0) NOT VALID,
    ADD CONSTRAINT conversion_rules_condition_object_check
        CHECK (jsonb_typeof(condition_json) = 'object') NOT VALID,
    ADD CONSTRAINT uk_conversion_rules_logical_version UNIQUE (logical_key, version_number);

CREATE UNIQUE INDEX uk_conversion_rules_active_logical
    ON public.conversion_rules (logical_key)
    WHERE is_active;

CREATE INDEX idx_conversion_rules_active_habit
    ON public.conversion_rules (habit_type, is_active, label);
