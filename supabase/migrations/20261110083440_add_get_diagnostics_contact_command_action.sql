alter table public.contact_bridge_commands
  drop constraint contact_bridge_commands_action_check;

alter table public.contact_bridge_commands
  add constraint contact_bridge_commands_action_check
  check (action = any (array[
    'list_contacts'::text,
    'search_contacts'::text,
    'upsert_contact'::text,
    'delete_contact'::text,
    'merge_contacts'::text,
    'sync_contacts'::text,
    'update_telegram_fields'::text,
    'move_contact'::text,
    'get_diagnostics'::text
  ]));
