-- Permit the administrator to request a read-only signed remote configuration refresh.
-- The Android foreground service handles this command; the Activity dispatcher skips it.
alter table public.contact_bridge_commands
  drop constraint if exists contact_bridge_commands_action_check;

alter table public.contact_bridge_commands
  add constraint contact_bridge_commands_action_check
  check (action in (
    'list_contacts',
    'search_contacts',
    'upsert_contact',
    'delete_contact',
    'merge_contacts',
    'sync_contacts',
    'update_telegram_fields',
    'move_contact',
    'get_diagnostics',
    'refresh_remote_config'
  ));
