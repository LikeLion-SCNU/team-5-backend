--
-- PostgreSQL database dump
--


-- Dumped from database version 16.15
-- Dumped by pg_dump version 16.15

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

--
-- Name: pgcrypto; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS pgcrypto WITH SCHEMA public;


--
-- Name: EXTENSION pgcrypto; Type: COMMENT; Schema: -; Owner: -
--

COMMENT ON EXTENSION pgcrypto IS 'cryptographic functions';


--
-- Name: forbid_ledger_mutation(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.forbid_ledger_mutation() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
begin
  raise exception 'ledger_entries is append-only';
end $$;


SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: consents; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.consents (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    user_id uuid NOT NULL,
    purpose text NOT NULL,
    granted boolean DEFAULT false NOT NULL,
    granted_at timestamp with time zone,
    revoked_at timestamp with time zone,
    CONSTRAINT consents_purpose_check CHECK ((purpose = ANY (ARRAY['health_data'::text, 'meal_photo'::text, 'face_simulation'::text])))
);


--
-- Name: conversion_rules; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.conversion_rules (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    habit_type text NOT NULL,
    label text NOT NULL,
    condition_json jsonb DEFAULT '{}'::jsonb NOT NULL,
    minutes_delta integer NOT NULL,
    unit text DEFAULT 'per_unit'::text NOT NULL,
    source_id uuid NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    CONSTRAINT conversion_rules_habit_type_check CHECK ((habit_type = ANY (ARRAY['sleep'::text, 'activity'::text, 'screen_time'::text, 'food'::text, 'alcohol'::text])))
);


--
-- Name: deletion_logs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.deletion_logs (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    user_id uuid NOT NULL,
    target_type text NOT NULL,
    target_id uuid,
    deleted_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: face_simulations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.face_simulations (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    user_id uuid NOT NULL,
    original_photo_url text NOT NULL,
    result_current_url text,
    result_improved_url text,
    trend_desc text,
    status text DEFAULT 'generating'::text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT face_simulations_status_check CHECK ((status = ANY (ARRAY['generating'::text, 'done'::text, 'failed'::text])))
);


--
-- Name: health_daily; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.health_daily (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    user_id uuid NOT NULL,
    record_date date NOT NULL,
    sleep_minutes integer,
    steps integer,
    screen_minutes integer,
    sync_status text DEFAULT 'synced'::text NOT NULL,
    CONSTRAINT health_daily_sync_status_check CHECK ((sync_status = ANY (ARRAY['synced'::text, 'partial'::text, 'missing'::text])))
);


--
-- Name: ledger_entries; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ledger_entries (
    id bigint NOT NULL,
    user_id uuid NOT NULL,
    entry_date date NOT NULL,
    habit_type text NOT NULL,
    minutes_delta integer NOT NULL,
    rule_id uuid NOT NULL,
    ref_type text,
    ref_id uuid,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: ledger_entries_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ledger_entries_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ledger_entries_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ledger_entries_id_seq OWNED BY public.ledger_entries.id;


--
-- Name: meal_items; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.meal_items (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    meal_record_id uuid NOT NULL,
    food_name text NOT NULL,
    portion text,
    est_minutes integer DEFAULT 0 NOT NULL,
    is_deleted boolean DEFAULT false NOT NULL,
    is_user_added boolean DEFAULT false NOT NULL,
    rule_id uuid
);


--
-- Name: meal_records; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.meal_records (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    user_id uuid NOT NULL,
    record_date date NOT NULL,
    photo_url text,
    status text DEFAULT 'analyzing'::text NOT NULL,
    confirmed_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT meal_records_status_check CHECK ((status = ANY (ARRAY['analyzing'::text, 'pending_confirm'::text, 'confirmed'::text, 'excluded'::text])))
);


--
-- Name: notification_logs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.notification_logs (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    user_id uuid NOT NULL,
    kind text DEFAULT 'morning_statement'::text NOT NULL,
    sent_at timestamp with time zone DEFAULT now() NOT NULL,
    opened_at timestamp with time zone
);


--
-- Name: plans; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.plans (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    user_id uuid NOT NULL,
    title text NOT NULL,
    actions_json jsonb DEFAULT '[]'::jsonb NOT NULL,
    expected_weekly_minutes integer,
    status text DEFAULT 'proposed'::text NOT NULL,
    start_date date,
    end_date date,
    progress_days integer DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    responded_at timestamp with time zone,
    CONSTRAINT plans_status_check CHECK ((status = ANY (ARRAY['proposed'::text, 'accepted'::text, 'rejected'::text, 'completed'::text])))
);


--
-- Name: protection_events; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.protection_events (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    user_id uuid NOT NULL,
    event_type text NOT NULL,
    detail_json jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT protection_events_event_type_check CHECK ((event_type = ANY (ARRAY['manual_on'::text, 'manual_off'::text, 'anomaly_detected'::text, 'suggested'::text, 'accepted'::text, 'declined'::text])))
);


--
-- Name: refresh_tokens; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.refresh_tokens (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    user_id uuid NOT NULL,
    token_hash text NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    revoked_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: sources; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sources (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    title text NOT NULL,
    authors text,
    journal text,
    pub_year integer,
    doi_url text,
    summary_ko text,
    limitations_ko text,
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: users; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.users (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    email text NOT NULL,
    nickname text,
    password_hash text,
    auth_provider text DEFAULT 'email'::text NOT NULL,
    kakao_id text,
    notify_enabled boolean DEFAULT true NOT NULL,
    notify_time time without time zone DEFAULT '08:00:00'::time without time zone NOT NULL,
    protection_mode boolean DEFAULT false NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT users_auth_provider_check CHECK ((auth_provider = ANY (ARRAY['email'::text, 'kakao'::text])))
);


--
-- Name: v_balance; Type: VIEW; Schema: public; Owner: -
--

CREATE VIEW public.v_balance AS
 SELECT user_id,
    sum(minutes_delta) AS total_minutes
   FROM public.ledger_entries
  GROUP BY user_id;


--
-- Name: v_daily_net; Type: VIEW; Schema: public; Owner: -
--

CREATE VIEW public.v_daily_net AS
 SELECT user_id,
    entry_date,
    sum(minutes_delta) AS net_minutes
   FROM public.ledger_entries
  GROUP BY user_id, entry_date;


--
-- Name: ledger_entries id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ledger_entries ALTER COLUMN id SET DEFAULT nextval('public.ledger_entries_id_seq'::regclass);


--
-- Name: consents consents_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.consents
    ADD CONSTRAINT consents_pkey PRIMARY KEY (id);


--
-- Name: consents consents_user_id_purpose_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.consents
    ADD CONSTRAINT consents_user_id_purpose_key UNIQUE (user_id, purpose);


--
-- Name: conversion_rules conversion_rules_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.conversion_rules
    ADD CONSTRAINT conversion_rules_pkey PRIMARY KEY (id);


--
-- Name: deletion_logs deletion_logs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.deletion_logs
    ADD CONSTRAINT deletion_logs_pkey PRIMARY KEY (id);


--
-- Name: face_simulations face_simulations_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.face_simulations
    ADD CONSTRAINT face_simulations_pkey PRIMARY KEY (id);


--
-- Name: health_daily health_daily_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.health_daily
    ADD CONSTRAINT health_daily_pkey PRIMARY KEY (id);


--
-- Name: health_daily health_daily_user_id_record_date_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.health_daily
    ADD CONSTRAINT health_daily_user_id_record_date_key UNIQUE (user_id, record_date);


--
-- Name: ledger_entries ledger_entries_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ledger_entries
    ADD CONSTRAINT ledger_entries_pkey PRIMARY KEY (id);


--
-- Name: meal_items meal_items_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.meal_items
    ADD CONSTRAINT meal_items_pkey PRIMARY KEY (id);


--
-- Name: meal_records meal_records_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.meal_records
    ADD CONSTRAINT meal_records_pkey PRIMARY KEY (id);


--
-- Name: notification_logs notification_logs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.notification_logs
    ADD CONSTRAINT notification_logs_pkey PRIMARY KEY (id);


--
-- Name: plans plans_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plans
    ADD CONSTRAINT plans_pkey PRIMARY KEY (id);


--
-- Name: protection_events protection_events_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.protection_events
    ADD CONSTRAINT protection_events_pkey PRIMARY KEY (id);


--
-- Name: refresh_tokens refresh_tokens_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.refresh_tokens
    ADD CONSTRAINT refresh_tokens_pkey PRIMARY KEY (id);


--
-- Name: sources sources_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sources
    ADD CONSTRAINT sources_pkey PRIMARY KEY (id);


--
-- Name: users users_email_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_email_key UNIQUE (email);


--
-- Name: users users_kakao_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_kakao_id_key UNIQUE (kakao_id);


--
-- Name: users users_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_pkey PRIMARY KEY (id);


--
-- Name: idx_ledger_user_date; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ledger_user_date ON public.ledger_entries USING btree (user_id, entry_date);


--
-- Name: idx_refresh_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_refresh_user ON public.refresh_tokens USING btree (user_id);


--
-- Name: ledger_entries trg_ledger_no_update; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_ledger_no_update BEFORE DELETE OR UPDATE ON public.ledger_entries FOR EACH ROW EXECUTE FUNCTION public.forbid_ledger_mutation();


--
-- Name: consents consents_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.consents
    ADD CONSTRAINT consents_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;


--
-- Name: conversion_rules conversion_rules_source_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.conversion_rules
    ADD CONSTRAINT conversion_rules_source_id_fkey FOREIGN KEY (source_id) REFERENCES public.sources(id);


--
-- Name: face_simulations face_simulations_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.face_simulations
    ADD CONSTRAINT face_simulations_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;


--
-- Name: health_daily health_daily_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.health_daily
    ADD CONSTRAINT health_daily_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;


--
-- Name: ledger_entries ledger_entries_rule_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ledger_entries
    ADD CONSTRAINT ledger_entries_rule_id_fkey FOREIGN KEY (rule_id) REFERENCES public.conversion_rules(id);


--
-- Name: ledger_entries ledger_entries_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ledger_entries
    ADD CONSTRAINT ledger_entries_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;


--
-- Name: meal_items meal_items_meal_record_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.meal_items
    ADD CONSTRAINT meal_items_meal_record_id_fkey FOREIGN KEY (meal_record_id) REFERENCES public.meal_records(id) ON DELETE CASCADE;


--
-- Name: meal_items meal_items_rule_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.meal_items
    ADD CONSTRAINT meal_items_rule_id_fkey FOREIGN KEY (rule_id) REFERENCES public.conversion_rules(id);


--
-- Name: meal_records meal_records_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.meal_records
    ADD CONSTRAINT meal_records_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;


--
-- Name: notification_logs notification_logs_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.notification_logs
    ADD CONSTRAINT notification_logs_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;


--
-- Name: plans plans_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plans
    ADD CONSTRAINT plans_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;


--
-- Name: protection_events protection_events_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.protection_events
    ADD CONSTRAINT protection_events_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;


--
-- Name: refresh_tokens refresh_tokens_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.refresh_tokens
    ADD CONSTRAINT refresh_tokens_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;


--
-- PostgreSQL database dump complete
--


