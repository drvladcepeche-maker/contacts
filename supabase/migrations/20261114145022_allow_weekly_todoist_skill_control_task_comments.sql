
insert into orchestrator.todoist_control_targets(owner_id,todoist_task_id,purpose,created_by)
values('690e2132-b3b8-4594-84f9-8c2b55b0d527','6j2PMXF3M2H8FX38','weekly-skill-control-review','690e2132-b3b8-4594-84f9-8c2b55b0d527')
on conflict(owner_id,todoist_task_id) do update set purpose=excluded.purpose,active=true,allow_comments=true;
