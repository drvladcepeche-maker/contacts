alter table public.telegram_match_jobs
  add column if not exists flood_wait_count integer not null default 0,
  add column if not exists last_flood_wait_seconds integer,
  add column if not exists last_flood_wait_at timestamptz;

comment on column public.telegram_match_jobs.flood_wait_count is
  'Number of Telegram FloodWait responses observed for this resolver job; used for conservative adaptive throttling.';
comment on column public.telegram_match_jobs.last_flood_wait_seconds is
  'Most recent Telegram-requested FloodWait duration in seconds.';
comment on column public.telegram_match_jobs.last_flood_wait_at is
  'Timestamp of the most recent Telegram FloodWait response.';
