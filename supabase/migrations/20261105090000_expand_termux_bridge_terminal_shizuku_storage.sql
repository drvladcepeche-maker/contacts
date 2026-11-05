-- Expand the Termux bridge with bounded shell sessions, scoped Shizuku file
-- operations, and private Storage artifact uploads. No table is exposed to clients.
alter table public.termux_bridge_jobs
  drop constraint if exists termux_bridge_jobs_command_type_check;

alter table public.termux_bridge_jobs
  add constraint termux_bridge_jobs_command_type_check
  check (command_type in (
    'pwd',
    'list_files',
    'git_status',
    'git_log',
    'git_diff_stat',
    'gradle_test',
    'gradle_assemble_debug',
    'apk_inspect',
    'file_read',
    'apk_inventory',
    'git_fetch_main',
    'node_check_bridge',
    'node_check_apk_inspect',
    'install_apk',
    'shizuku_storage_check',
    'terminal_run',
    'terminal_start',
    'terminal_input',
    'terminal_read',
    'terminal_stop',
    'shizuku_file_list',
    'shizuku_file_mkdir',
    'shizuku_file_copy',
    'shizuku_file_move',
    'shizuku_file_delete',
    'file_upload'
  ));

insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('termux-bridge-artifacts', 'termux-bridge-artifacts', false, 52428800, null)
on conflict (id) do update
set public = false,
    file_size_limit = 52428800,
    allowed_mime_types = null;
