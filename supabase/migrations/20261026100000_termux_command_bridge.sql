-- Termux command bridge: source-controlled schema and atomic job claiming.
-- Existing live tables are preserved; this migration is safe to apply to a fresh project too.

create table if not exists public.termux_bridge_devices (
  id uuid primary key default gen_random_uuid(),
  label text not null default 'Android Termux',
  token_hash text not null unique check (token_hash ~ '^[0-9a-f]{64}$'),
  enabled boolean not null default true,
  created_at timestamptz not null default now(),
  last_seen_at timestamptz,
  revoked_at timestamptz
);

create table if not exists public.termux_bridge_jobs (
  id uuid primary key default gen_random_uuid(),
  device_id uuid not null references public.termux_bridge_devices(id),
  command_type text not null check (command_type in (
    'pwd', 'list_files', 'git_status', 'git_log', 'git_diff_stat',
    'gradle_test', 'gradle_assemble_debug'
  )),
  args jsonb not null default '{}'::jsonb check (jsonb_typeof(args) = 'object'),
  status text not null default 'queued' check (status in (
    'queued', 'running', 'succeeded', 'failed', 'cancelled'
  )),
  output text,
  error_text text,
  exit_code integer,
  created_at timestamptz not null default now(),
  started_at timestamptz,
  finished_at timestamptz
);

create index if not exists termux_bridge_jobs_claim_idx
  on public.termux_bridge_jobs (device_id, status, created_at);

alter table public.termux_bridge_devices enable row level security;
alter table public.termux_bridge_jobs enable row level security;
revoke all on public.termux_bridge_devices, public.termux_bridge_jobs from anon, authenticated;
grant all on public.termux_bridge_devices, public.termux_bridge_jobs to service_role;

create or replace function public.claim_termux_bridge_job(p_device_id uuid)
returns setof public.termux_bridge_jobs
language sql
security definer
set search_path = public, pg_temp
as $$
  with next_job as (
    select id
    from public.termux_bridge_jobs
    where device_id = p_device_id and status = 'queued'
    order by created_at asc
    limit 1
    for update skip locked
  )
  update public.termux_bridge_jobs as job
  set status = 'running', started_at = now()
  from next_job
  where job.id = next_job.id
  returning job.*;
$$;

revoke all on function public.claim_termux_bridge_job(uuid) from public, anon, authenticated;
grant execute on function public.claim_termux_bridge_job(uuid) to service_role;
