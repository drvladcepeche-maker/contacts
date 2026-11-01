-- Keep one copy of each identical queue index and index the worker lease FK.
-- This migration is safe to re-run: create/drop statements are guarded.
create index if not exists psurvival_bot_jobs_worker_id_idx
  on public.psurvival_bot_jobs (worker_id);

drop index if exists public.telegram_match_jobs_ready_idx;
drop index if exists public.termux_bridge_jobs_device_status_idx;
