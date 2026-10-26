-- Queue for the standalone Telegram matching worker.
-- Apply in Supabase SQL Editor or through the project's migration workflow.
create table if not exists public.telegram_match_jobs (
  id uuid primary key default gen_random_uuid(),
  status text not null default 'queued' check (status in ('queued','running','completed','failed')),
  phones jsonb not null default '[]'::jsonb,
  results jsonb not null default '[]'::jsonb,
  checked integer not null default 0,
  matched integer not null default 0,
  errors integer not null default 0,
  attempts integer not null default 0,
  error_message text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  claimed_at timestamptz,
  completed_at timestamptz,
  constraint telegram_match_jobs_phones_array check (jsonb_typeof(phones) = 'array')
);

create index if not exists telegram_match_jobs_queue_idx
  on public.telegram_match_jobs (created_at)
  where status = 'queued';

alter table public.telegram_match_jobs enable row level security;
-- No client-facing policies: only the server-side service-role worker should access this queue.

create or replace function public.claim_telegram_match_job()
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  claimed public.telegram_match_jobs;
begin
  select * into claimed
  from public.telegram_match_jobs
  where status = 'queued'
  order by created_at asc
  for update skip locked
  limit 1;

  if not found then
    return null;
  end if;

  update public.telegram_match_jobs
  set status = 'running',
      claimed_at = now(),
      updated_at = now(),
      attempts = attempts + 1
  where id = claimed.id
  returning * into claimed;

  return to_jsonb(claimed);
end;
$$;

revoke all on function public.claim_telegram_match_job() from public, anon, authenticated;
grant execute on function public.claim_telegram_match_job() to service_role;
revoke all on table public.telegram_match_jobs from anon, authenticated;
grant select, insert, update on table public.telegram_match_jobs to service_role;
