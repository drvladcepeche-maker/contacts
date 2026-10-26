do $$
declare c record;
begin
  for c in
    select conname
    from pg_constraint
    where conrelid = 'public.termux_bridge_jobs'::regclass
      and contype = 'c'
      and pg_get_constraintdef(oid) ilike '%command_type%'
  loop
    execute format('alter table public.termux_bridge_jobs drop constraint %I', c.conname);
  end loop;
end $$;

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
    'apk_inspect'
  ));
