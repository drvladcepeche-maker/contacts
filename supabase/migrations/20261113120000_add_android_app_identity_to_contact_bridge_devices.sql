alter table public.contact_bridge_devices
  add column if not exists app_package_name text,
  add column if not exists app_version_name text,
  add column if not exists app_version_code bigint,
  add column if not exists app_build_type text,
  add column if not exists app_reported_at timestamptz;

create index if not exists contact_bridge_devices_app_version_idx
  on public.contact_bridge_devices (app_package_name, app_version_code desc);
