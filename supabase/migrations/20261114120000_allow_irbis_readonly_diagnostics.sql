-- Permit the two fixed, read-only diagnostics implemented by the IRBIS Python worker.
-- Preserve every command currently allowed by the bridge queue.
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
    'file_upload',
    'brave_accessibility',
    'device_info',
    'shizuku_status'
  ));
