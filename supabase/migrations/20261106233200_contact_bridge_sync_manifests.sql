-- Record the exact Android contact ID set for each completed full sync.
-- This is an audit manifest only: it never deletes or modifies contact snapshots.
create table if not exists public.contact_bridge_sync_manifests (
  device_id uuid not null references public.contact_bridge_devices(id),
  sync_id uuid not null,
  local_contact_ids text[] not null default '{}',
  contact_count integer not null
    check (contact_count between 0 and 25000)
    check (contact_count = cardinality(local_contact_ids)),
  sync_summary jsonb not null default '{}'::jsonb
    check (jsonb_typeof(sync_summary) = 'object'),
  created_at timestamptz not null default now(),
  primary key (device_id, sync_id),
  check (array_position(local_contact_ids, null) is null)
);

create index if not exists contact_bridge_sync_manifests_latest_idx
  on public.contact_bridge_sync_manifests (device_id, created_at desc);

-- The manifest is private server-side metadata, not a public Data API resource.
alter table public.contact_bridge_sync_manifests enable row level security;
revoke all on table public.contact_bridge_sync_manifests from public, anon, authenticated;
grant all on table public.contact_bridge_sync_manifests to service_role;
