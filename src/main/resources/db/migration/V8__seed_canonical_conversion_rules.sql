ALTER TABLE public.health_daily
    ADD COLUMN moderate_activity_minutes integer,
    ADD CONSTRAINT health_daily_moderate_activity_minutes_check
        CHECK (moderate_activity_minutes BETWEEN 0 AND 1440) NOT VALID;

CREATE TEMPORARY TABLE canonical_source_seed_v8 (
    id uuid PRIMARY KEY,
    logical_key uuid NOT NULL,
    version_number integer NOT NULL,
    title text NOT NULL,
    authors text,
    journal text,
    pub_year integer,
    doi_url text,
    summary_ko text NOT NULL,
    scope_ko text NOT NULL,
    limitations_ko text NOT NULL
) ON COMMIT DROP;

INSERT INTO canonical_source_seed_v8
    (id, logical_key, version_number, title, authors, journal, pub_year, doi_url,
     summary_ko, scope_ko, limitations_ko)
VALUES
    (
        '11000000-0000-0000-0000-000000000001',
        '11000000-0000-0000-0000-000000000001',
        1,
        '[MEASURED] Moderate-activity microlife association',
        'David Spiegelhalter',
        'BMJ',
        2012,
        'https://doi.org/10.1136/bmj.e8223',
        '중간 강도 운동 20분/일을 +2 microlives(+60분)로 표현한 인구 수준의 위험 커뮤니케이션 값이다.',
        '[DERIVED] +60/20=+3분을 중간 강도 운동 1분당 적용하고, 하루 최대 20분까지 입력 단계에서 제한합니다.',
        'illustrative, population-level association; not an individual lifespan prediction or medical advice. 전체 보행 수를 중간 강도 운동으로 대리하지 않으며 일회성 활동의 인과적 수명 증가를 의미하지 않습니다.'
    ),
    (
        '11000000-0000-0000-0000-000000000002',
        '11000000-0000-0000-0000-000000000002',
        1,
        '[MEASURED] Lifetime television-viewing association after age 25',
        'J Lennert Veerman et al.',
        'British Journal of Sports Medicine',
        2012,
        'https://doi.org/10.1136/bjsm.2011.085662',
        '25세 이후 평생 TV 시청 1시간당 기대수명 21.8분 감소와 연관된 생명표 분석입니다.',
        '[MEASURED] 발표값 21.8분을 정수 -22분/평생 TV 시청 시간으로 반올림합니다.',
        'illustrative, population-level association; not an individual lifespan prediction or medical advice. 일반 스크린 시간이나 좌식 시간에 대리 적용하지 않으며, 인과효과를 입증하지 않습니다.'
    ),
    (
        '11000000-0000-0000-0000-000000000003',
        '11000000-0000-0000-0000-000000000003',
        1,
        '[MEASURED + DERIVED] Short-sleep mortality association mapping',
        'Francesco P Cappuccio et al.; Time Bank derivation',
        'Sleep',
        2010,
        'https://doi.org/10.1093/sleep/33.5.585',
        '메타분석의 short-sleep category RR 1.12는 짧은 수면 범주와 전체 사망의 관련성을 보고합니다.',
        '[DERIVED] -30 x ln(1.12) / ln(1.1) = -35.67분을 -36분으로 반올하여 one short-sleep day below seven hours에 1회 적용합니다.',
        'illustrative, population-level association; not an individual lifespan prediction or medical advice. 이 논문은 시간당 손실을 입증하지 않으며, 7시간 미만 범주 효과의 제품 환산은 DERIVED입니다.'
    ),
    (
        '11000000-0000-0000-0000-000000000004',
        '11000000-0000-0000-0000-000000000004',
        1,
        '[MEASURED + DERIVED] Extra-alcohol microlife allocation',
        'David Spiegelhalter; Time Bank allocation',
        'BMJ',
        2012,
        'https://doi.org/10.1136/bmj.e8223',
        '추가 술 2잔/일을 -1 microlife(-30분)로 표현한 인구 수준 커뮤니케이션 값입니다.',
        '[DERIVED] -30/2=-15분을 drinks above the first에만 적용합니다. 첫 잔은 중립으로 처리하며 no positive alcohol credit을 보장합니다.',
        'illustrative, population-level association; not an individual lifespan prediction or medical advice. 인구집단과 성별 차이, 비선형성, 교란을 개인의 원인적 수명 변화로 해석하지 않습니다.'
    ),
    (
        '11000000-0000-0000-0000-000000000005',
        '11000000-0000-0000-0000-000000000005',
        1,
        '[MEASURED + DERIVED] Qualified fruit-and-vegetable serving mapping',
        'David Spiegelhalter; Dong D Wang et al.; Time Bank derivation',
        'BMJ and Circulation',
        2021,
        'https://doi.org/10.1161/CIRCULATIONAHA.120.048996',
        'Wang 2021은 하루 약 5회 섭취에서 최저 사망률 연관성과 그 이상의 plateau를 보고합니다. Spiegelhalter 정정표의 보수적 여성 값은 5회분 +90분/일입니다.',
        '[DERIVED] +90/5=+18분을 적합한 과일·비전분 채소 서빙에 배분하고 five qualifying servings per day로 제한합니다.',
        'illustrative, population-level association; not an individual lifespan prediction or medical advice. 24 minutes was rejected as a male-specific overgeneralization. 개별 서빙의 즉시적·인과적 수명 증가를 의미하지 않습니다.'
    );

INSERT INTO public.sources
    (id, logical_key, version_number, title, authors, journal, pub_year, doi_url,
     summary_ko, scope_ko, limitations_ko, is_active, row_version, created_at, updated_at)
SELECT id, logical_key, version_number, title, authors, journal, pub_year, doi_url,
       summary_ko, scope_ko, limitations_ko, true, 0,
       '2026-08-21 00:00:00+00'::timestamptz, '2026-08-21 00:00:00+00'::timestamptz
FROM canonical_source_seed_v8
ON CONFLICT (logical_key, version_number) DO NOTHING;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM canonical_source_seed_v8 seed
        LEFT JOIN public.sources stored
          ON stored.logical_key = seed.logical_key
         AND stored.version_number = seed.version_number
        WHERE stored.id IS NULL
           OR stored.id <> seed.id
           OR stored.title <> seed.title
           OR stored.authors IS DISTINCT FROM seed.authors
           OR stored.journal IS DISTINCT FROM seed.journal
           OR stored.pub_year IS DISTINCT FROM seed.pub_year
           OR stored.doi_url IS DISTINCT FROM seed.doi_url
           OR stored.summary_ko IS DISTINCT FROM seed.summary_ko
           OR stored.scope_ko IS DISTINCT FROM seed.scope_ko
           OR stored.limitations_ko IS DISTINCT FROM seed.limitations_ko
           OR stored.is_active IS NOT TRUE
    ) THEN
        RAISE EXCEPTION 'CANONICAL_SOURCE_CONFLICT';
    END IF;
END $$;

CREATE TEMPORARY TABLE canonical_rule_seed_v8 (
    habit_type text NOT NULL,
    minutes_delta integer NOT NULL,
    unit text NOT NULL,
    evidence_class text NOT NULL,
    id uuid PRIMARY KEY,
    logical_key uuid NOT NULL,
    label text NOT NULL,
    source_id uuid NOT NULL
) ON COMMIT DROP;

INSERT INTO canonical_rule_seed_v8
    (habit_type, minutes_delta, unit, evidence_class, id, logical_key, label, source_id)
VALUES
    ('activity', 3, 'per_minute', 'DERIVED',
     '21000000-0000-0000-0000-000000000001', '21000000-0000-0000-0000-000000000001',
     '[DERIVED] +3 min per moderate-activity minute, capped at 20 per day',
     '11000000-0000-0000-0000-000000000001'),
    ('screen_time', -22, 'per_hour', 'MEASURED',
     '21000000-0000-0000-0000-000000000002', '21000000-0000-0000-0000-000000000002',
     '[MEASURED] -22 min per lifetime TV-viewing hour after age 25',
     '11000000-0000-0000-0000-000000000002'),
    ('sleep', -36, 'per_unit', 'DERIVED',
     '21000000-0000-0000-0000-000000000003', '21000000-0000-0000-0000-000000000003',
     '[DERIVED] -36 min per short-sleep day below 7 hours',
     '11000000-0000-0000-0000-000000000003'),
    ('alcohol', -15, 'per_drink', 'DERIVED',
     '21000000-0000-0000-0000-000000000004', '21000000-0000-0000-0000-000000000004',
     '[DERIVED] -15 min per drink above the first',
     '11000000-0000-0000-0000-000000000004'),
    ('food', 18, 'per_serving', 'DERIVED',
     '21000000-0000-0000-0000-000000000005', '21000000-0000-0000-0000-000000000005',
     '[DERIVED] +18 min per qualifying serving, capped at 5 per day',
     '11000000-0000-0000-0000-000000000005');

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM public.conversion_rules stored
        JOIN canonical_rule_seed_v8 seed
          ON stored.habit_type::text = seed.habit_type
         AND lower(btrim(stored.unit)) = seed.unit
        WHERE stored.is_active IS TRUE
          AND stored.logical_key <> seed.logical_key
    ) THEN
        RAISE EXCEPTION 'CANONICAL_ACTIVE_SELECTOR_CONFLICT';
    END IF;
END $$;

INSERT INTO public.conversion_rules
    (id, logical_key, version_number, habit_type, label, condition_json, minutes_delta,
     unit, source_id, is_active, row_version, created_at, updated_at)
SELECT id, logical_key, 1, habit_type, label, '{}'::jsonb, minutes_delta,
       unit, source_id, true, 0,
       '2026-08-21 00:00:00+00'::timestamptz, '2026-08-21 00:00:00+00'::timestamptz
FROM canonical_rule_seed_v8
ON CONFLICT (logical_key, version_number) DO NOTHING;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM canonical_rule_seed_v8 seed
        LEFT JOIN public.conversion_rules stored
          ON stored.logical_key = seed.logical_key
         AND stored.version_number = 1
        WHERE stored.id IS NULL
           OR stored.id <> seed.id
           OR stored.habit_type::text <> seed.habit_type
           OR stored.label <> seed.label
           OR stored.condition_json <> '{}'::jsonb
           OR stored.minutes_delta <> seed.minutes_delta
           OR lower(btrim(stored.unit)) <> seed.unit
           OR stored.source_id <> seed.source_id
           OR stored.is_active IS NOT TRUE
    ) THEN
        RAISE EXCEPTION 'CANONICAL_RULE_CONFLICT';
    END IF;
END $$;
