-- Safe resumable phone resolution using contacts.resolvePhone (no contact import).
alter table public.telegram_match_jobs
  add column if not exists paused_until timestamptz,
  add column if not exists last_request_at timestamptz,
  add column if not exists requested_by_telegram_user_id bigint;

create index if not exists telegram_match_jobs_ready_idx
  on public.telegram_match_jobs (created_at)
  where status = 'queued';

create or replace function public.claim_telegram_match_job()
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  claimed public.telegram_match_jobs;
begin
  -- Recover a worker crash without losing already persisted per-phone results.
  update public.telegram_match_jobs
  set status = 'queued', claimed_at = null, updated_at = now()
  where status = 'running'
    and claimed_at < now() - interval '30 minutes';

  select * into claimed
  from public.telegram_match_jobs
  where status = 'queued'
    and (paused_until is null or paused_until <= now())
  order by created_at asc
  for update skip locked
  limit 1;

  if not found then return null; end if;

  update public.telegram_match_jobs
  set status = 'running', claimed_at = now(), updated_at = now(),
      attempts = attempts + 1
  where id = claimed.id
  returning * into claimed;

  return to_jsonb(claimed);
end;
$$;

revoke all on function public.claim_telegram_match_job() from public, anon, authenticated;
grant execute on function public.claim_telegram_match_job() to service_role;
