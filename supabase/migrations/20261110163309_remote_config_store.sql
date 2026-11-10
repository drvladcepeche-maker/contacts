-- Server-only configuration source for signed Android remote settings.
-- The table is in the exposed public schema but has RLS enabled and no
-- privileges for anon/authenticated/PUBLIC; only service_role can read it.

create table if not exists public.remote_app_config (
  id smallint primary key default 1 check (id = 1),
  sequence bigint not null check (sequence between 1 and 9007199254740991),
  issued_at bigint not null check (issued_at >= 0),
  expires_at bigint not null check (expires_at > issued_at),
  config jsonb not null check (jsonb_typeof(config) = 'object'),
  updated_at timestamptz not null default now()
);

alter table public.remote_app_config enable row level security;

revoke all on table public.remote_app_config from public, anon, authenticated;
grant select on table public.remote_app_config to service_role;

insert into public.remote_app_config (
  id, sequence, issued_at, expires_at, config
)
values (
  1,
  1,
  floor(extract(epoch from now()))::bigint,
  floor(extract(epoch from now() + interval '7 days'))::bigint,
  '{"features":{"telegram_match_enabled":false,"brave_automation_enabled":false},"sync":{"poll_interval_seconds":60}}'::jsonb
)
on conflict (id) do nothing;
