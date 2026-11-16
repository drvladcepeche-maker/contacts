create table if not exists public.contact_bridge_diagnostics (
  id uuid primary key default gen_random_uuid(),
  device_id uuid not null references public.contact_bridge_devices(id) on delete cascade,
  event_id text not null,
  occurred_at timestamptz not null,
  level text not null check (level = any (array['INFO','WARN','ERROR','FATAL']::text[])),
  tag text not null,
  message text not null,
  stack_trace text,
  app_version text,
  created_at timestamptz not null default now(),
  constraint contact_bridge_diagnostics_device_id_event_id_key unique (device_id, event_id)
);

create index if not exists contact_bridge_diagnostics_device_created_idx
  on public.contact_bridge_diagnostics (device_id, created_at desc);

alter table public.contact_bridge_diagnostics enable row level security;
revoke all on table public.contact_bridge_diagnostics from anon, authenticated, public;
grant all on table public.contact_bridge_diagnostics to service_role;
