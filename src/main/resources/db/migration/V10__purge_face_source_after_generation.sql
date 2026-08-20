-- 생성이 끝난 얼굴 시뮬레이션은 원본 사진을 즉시 파기한다(업로드 화면의 고지 이행).
-- 파기 후에도 시뮬레이션 이력은 남아야 하므로 원본 참조를 비울 수 있게 한다.
ALTER TABLE public.face_simulations
    ALTER COLUMN source_media_id DROP NOT NULL;

-- 기존 가드는 source_media_id 비우기를 전면 금지했다.
-- 완료(done)로 전이하는 경우에만 허용하도록 좁히고, 나머지 불변식은 그대로 유지한다.
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
        -- 결과 이미지가 남은 완료 건에 한해 원본 파기를 허용한다.
        IF OLD.source_media_id IS NOT NULL AND NEW.source_media_id IS NULL
            AND NEW.status <> 'done' THEN
            RAISE EXCEPTION 'face_simulations source_media_id can only be cleared once generation is done'
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
