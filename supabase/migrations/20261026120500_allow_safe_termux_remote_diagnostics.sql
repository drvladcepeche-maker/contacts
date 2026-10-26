alter table public.termux_bridge_jobs
  drop constraint termux_bridge_jobs_command_type_check;

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
    'node_check_apk_inspect'
  ));
