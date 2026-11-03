-- Additive, private evidence tables for cross-field identifier classification and Sherlock.
-- Original contact snapshots are never modified by this migration or worker.

create table if not exists public.contact_identifier_observations (
  id uuid primary key default gen_random_uuid(),
  device_id uuid not null,
  local_contact_id text not null,
  source_path text not null,
  source_mimetype text,
  source_value text not null,
  detected_type text not null,
  expected_type text,
  normalized_value text,
  confidence text not null default 'medium',
  is_misfiled boolean not null default false,
  first_seen_at timestamptz not null default now(),
  last_seen_at timestamptz not null default now(),
  created_at timestamptz not null default now(),
  unique (device_id, local_contact_id, source_path, source_value, detected_type)
);

create index if not exists contact_identifier_observations_contact_idx
  on public.contact_identifier_observations (device_id, local_contact_id);
create index if not exists contact_identifier_observations_type_idx
  on public.contact_identifier_observations (detected_type, is_misfiled);

create table if not exists public.contact_osint_profiles (
  id uuid primary key default gen_random_uuid(),
  device_id uuid not null,
  local_contact_id text not null,
  source_path text not null,
  normalized_username text not null,
  platform text not null,
  profile_url text,
  result_status text not null check (result_status in ('CLAIMED','AVAILABLE','UNKNOWN','ILLEGAL','WAF','ERROR')),
  http_status integer,
  response_time_s numeric,
  checked_at timestamptz not null default now(),
  manifest_version text not null,
  evidence jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (device_id, local_contact_id, normalized_username, platform)
);

create index if not exists contact_osint_profiles_username_idx
  on public.contact_osint_profiles (normalized_username, checked_at desc);
create index if not exists contact_osint_profiles_status_idx
  on public.contact_osint_profiles (result_status, checked_at desc);

alter table public.contact_identifier_observations enable row level security;
alter table public.contact_osint_profiles enable row level security;

revoke all on public.contact_identifier_observations from anon, authenticated;
revoke all on public.contact_osint_profiles from anon, authenticated;
grant all on public.contact_identifier_observations to service_role;
grant all on public.contact_osint_profiles to service_role;
