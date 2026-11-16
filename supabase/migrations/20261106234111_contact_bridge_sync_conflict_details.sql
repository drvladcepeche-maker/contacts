-- Persist per-contact conflict IDs and field names for future sync audits.
-- This is diagnostic metadata only; it does not modify contact snapshots.
alter table public.contact_bridge_sync_manifests
  add column if not exists conflict_details jsonb not null default '[]'::jsonb
  check (jsonb_typeof(conflict_details) = 'array');

revoke all on table public.contact_bridge_sync_manifests from public, anon, authenticated;
grant all on table public.contact_bridge_sync_manifests to service_role;
