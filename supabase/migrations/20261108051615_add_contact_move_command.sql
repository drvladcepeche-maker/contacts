alter table public.contact_bridge_commands
  drop constraint if exists contact_bridge_commands_action_check;

alter table public.contact_bridge_commands
  add constraint contact_bridge_commands_action_check
  check (action = any (array[
    'list_contacts',
    'search_contacts',
    'upsert_contact',
    'delete_contact',
    'merge_contacts',
    'sync_contacts',
    'update_telegram_fields',
    'move_contact'
  ]::text[]));
