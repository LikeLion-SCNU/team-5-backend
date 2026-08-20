ALTER TABLE public.ledger_entries
    ADD CONSTRAINT uk_ledger_entries_user_id_id UNIQUE (user_id, id);

CREATE TABLE public.conversion_postings (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    user_id uuid NOT NULL,
    source_event_id uuid NOT NULL,
    source_event_type text NOT NULL,
    entry_date date NOT NULL,
    habit_type text NOT NULL,
    input_value numeric(22, 12) NOT NULL,
    input_unit text NOT NULL,
    posted_seconds bigint NOT NULL,
    ledger_minutes_delta integer NOT NULL,
    rule_id uuid NOT NULL,
    source_id uuid NOT NULL,
    ledger_entry_id bigint NOT NULL,
    request_hash character varying(64) NOT NULL,
    rule_snapshot_json jsonb NOT NULL,
    source_snapshot_json jsonb NOT NULL,
    input_snapshot_json jsonb NOT NULL,
    result_snapshot_json jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT conversion_postings_pkey PRIMARY KEY (id),
    CONSTRAINT conversion_postings_user_id_fkey
        FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE,
    CONSTRAINT conversion_postings_rule_id_fkey
        FOREIGN KEY (rule_id) REFERENCES public.conversion_rules(id),
    CONSTRAINT conversion_postings_source_id_fkey
        FOREIGN KEY (source_id) REFERENCES public.sources(id),
    CONSTRAINT conversion_postings_ledger_entry_id_fkey
        FOREIGN KEY (ledger_entry_id) REFERENCES public.ledger_entries(id),
    CONSTRAINT conversion_postings_user_ledger_fkey
        FOREIGN KEY (user_id, ledger_entry_id) REFERENCES public.ledger_entries(user_id, id),
    CONSTRAINT conversion_postings_source_event_type_check
        CHECK (source_event_type IN ('health_daily', 'meal_item')),
    CONSTRAINT conversion_postings_habit_type_check
        CHECK (habit_type IN ('sleep', 'activity', 'screen_time', 'food', 'alcohol')),
    CONSTRAINT conversion_postings_input_value_check
        CHECK (input_value >= 0 AND input_value <= 1000000000),
    CONSTRAINT conversion_postings_posted_seconds_check
        CHECK (abs(posted_seconds) <= 31536000000),
    CONSTRAINT conversion_postings_request_hash_check
        CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT conversion_postings_rule_snapshot_object_check
        CHECK (jsonb_typeof(rule_snapshot_json) = 'object'),
    CONSTRAINT conversion_postings_source_snapshot_object_check
        CHECK (jsonb_typeof(source_snapshot_json) = 'object'),
    CONSTRAINT conversion_postings_input_snapshot_object_check
        CHECK (jsonb_typeof(input_snapshot_json) = 'object'),
    CONSTRAINT conversion_postings_result_snapshot_object_check
        CHECK (jsonb_typeof(result_snapshot_json) = 'object'),
    CONSTRAINT uk_conversion_postings_user_source_metric
        UNIQUE (user_id, source_event_type, source_event_id, habit_type),
    CONSTRAINT uk_conversion_postings_ledger_entry UNIQUE (ledger_entry_id)
);

CREATE INDEX idx_conversion_postings_user_date
    ON public.conversion_postings (user_id, entry_date DESC, created_at DESC);

CREATE FUNCTION public.forbid_conversion_posting_mutation() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    RAISE EXCEPTION 'conversion_postings is append-only';
END $$;

CREATE TRIGGER trg_conversion_postings_no_update
    BEFORE DELETE OR UPDATE ON public.conversion_postings
    FOR EACH ROW EXECUTE FUNCTION public.forbid_conversion_posting_mutation();
