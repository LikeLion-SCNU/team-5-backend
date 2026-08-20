UPDATE public.notification_preferences
SET timezone = 'Asia/Seoul'
WHERE timezone IS DISTINCT FROM 'Asia/Seoul';

ALTER TABLE public.notification_preferences
    ALTER COLUMN timezone SET DEFAULT 'Asia/Seoul',
    ALTER COLUMN timezone SET NOT NULL;

ALTER TABLE public.notification_preferences
    DROP CONSTRAINT IF EXISTS notification_preferences_timezone_check;

ALTER TABLE public.notification_preferences
    ADD CONSTRAINT notification_preferences_timezone_check
    CHECK (timezone = 'Asia/Seoul');
