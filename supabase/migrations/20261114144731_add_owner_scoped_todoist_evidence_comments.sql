
alter table orchestrator.worker_credentials drop constraint worker_credentials_scopes_check;
alter table orchestrator.worker_credentials add constraint worker_credentials_scopes_check
 check (
  cardinality(scopes) between 1 and 6
  and scopes <@ array['queue.claim','queue.complete','queue.renew','queue.fail','skill.evaluation.append','todoist.update']::text[]
 );

alter table orchestrator.worker_credentials drop constraint worker_credentials_skill_evaluator_scope_check;
alter table orchestrator.worker_credentials add constraint worker_credentials_skill_control_scope_check
 check (
  worker_id <> 'osiris-skill-control'
  or (
   owner_id is not null
   and allowed_task_types = array['skill.evaluate']::text[]
   and scopes = array['queue.claim','queue.complete','queue.renew','queue.fail','skill.evaluation.append','todoist.update']::text[]
  )
 );

create table orchestrator.todoist_control_targets (
 owner_id uuid not null references auth.users(id) on delete restrict,
 todoist_task_id text not null check (length(btrim(todoist_task_id)) between 1 and 64),
 purpose text not null check (length(btrim(purpose)) between 1 and 100),
 allow_comments boolean not null default true,
 active boolean not null default true,
 created_by uuid not null references auth.users(id) on delete restrict,
 created_at timestamptz not null default clock_timestamp(),
 primary key(owner_id,todoist_task_id)
);
alter table orchestrator.todoist_control_targets enable row level security;
alter table orchestrator.todoist_control_targets force row level security;
revoke all on table orchestrator.todoist_control_targets from public,anon,authenticated,service_role;
create index todoist_control_targets_active_idx
 on orchestrator.todoist_control_targets(owner_id,active) where active and allow_comments;

insert into orchestrator.todoist_control_targets(owner_id,todoist_task_id,purpose,created_by)
values
 ('690e2132-b3b8-4594-84f9-8c2b55b0d527','6j2PMXGGMGC4hwVg','skill-lifecycle-ledger','690e2132-b3b8-4594-84f9-8c2b55b0d527'),
 ('690e2132-b3b8-4594-84f9-8c2b55b0d527','6j2PMXFh96G878fg','todoist-worker-bridge','690e2132-b3b8-4594-84f9-8c2b55b0d527'),
 ('690e2132-b3b8-4594-84f9-8c2b55b0d527','6j2PMXGqfH4R5478','skill-resolver-dry-run','690e2132-b3b8-4594-84f9-8c2b55b0d527'),
 ('690e2132-b3b8-4594-84f9-8c2b55b0d527','6j2PQWxvcVQwV8gg','four-eyes-actor-setup','690e2132-b3b8-4594-84f9-8c2b55b0d527'),
 ('690e2132-b3b8-4594-84f9-8c2b55b0d527','6j2PQWwVgfJGgQWg','worker-credential-live-test','690e2132-b3b8-4594-84f9-8c2b55b0d527'),
 ('690e2132-b3b8-4594-84f9-8c2b55b0d527','6j2PQf9FGvXcqqwg','policy-registry-and-actor-audit','690e2132-b3b8-4594-84f9-8c2b55b0d527')
on conflict(owner_id,todoist_task_id) do update set purpose=excluded.purpose,active=true,allow_comments=true;

create or replace function orchestrator.issue_skill_evaluation_worker_credential_internal(p_owner_id uuid)
returns text language plpgsql security definer set search_path=''
as $function$
declare v_token text; v_hash text; v_prefix text; v_now timestamptz:=clock_timestamp();
begin
 if auth.uid() is null or auth.uid()<>p_owner_id then
  raise exception 'authenticated project owner identity required';
 end if;
 update orchestrator.worker_credentials set revoked_at=v_now
 where owner_id=p_owner_id and worker_id='osiris-skill-control' and revoked_at is null;
 v_token:='orch_'||rtrim(translate(encode(extensions.gen_random_bytes(48),'base64'),'+/','-_'),'=');
 v_hash:=pg_catalog.encode(extensions.digest(pg_catalog.convert_to(v_token,'UTF8'),'sha256'),'hex');
 v_prefix:=left(v_token,16);
 insert into orchestrator.worker_credentials(owner_id,worker_id,token_hash,token_prefix,scopes,allowed_task_types,created_at,expires_at)
 values(p_owner_id,'osiris-skill-control',v_hash,v_prefix,
  array['queue.claim','queue.complete','queue.renew','queue.fail','skill.evaluation.append','todoist.update']::text[],
  array['skill.evaluate']::text[],v_now,v_now+interval '48 hours');
 return v_token;
end;
$function$;

create or replace function orchestrator.revoke_skill_evaluation_worker_credential_internal(p_owner_id uuid)
returns integer language plpgsql security definer set search_path=''
as $function$
declare v_count integer;
begin
 if auth.uid() is null or auth.uid()<>p_owner_id then
  raise exception 'authenticated project owner identity required';
 end if;
 update orchestrator.worker_credentials set revoked_at=clock_timestamp()
 where owner_id=p_owner_id and worker_id='osiris-skill-control' and revoked_at is null;
 get diagnostics v_count=row_count;
 return v_count;
end;
$function$;

create or replace function orchestrator.claim_skill_evaluation_task_scoped(
 p_worker_id text,p_owner_id uuid,p_lease_seconds integer
) returns jsonb language plpgsql security definer set search_path=''
as $function$
declare v_task orchestrator.orchestrator_tasks%rowtype;
 v_lease_seconds integer:=least(greatest(coalesce(p_lease_seconds,120),30),300);
begin
 if p_worker_id<>'osiris-skill-control' or p_owner_id is null then
  raise exception 'invalid Osiris skill control worker identity';
 end if;
 update orchestrator.orchestrator_tasks
 set status='failed',error_message='Lease expired; maximum attempts reached',
  worker_id=null,lease_token=null,lease_expires_at=null,completed_at=now()
 where status='running' and lease_expires_at<=now() and attempt_count>=max_attempts
  and task_type='skill.evaluate' and payload->>'owner_id'=p_owner_id::text;
 with candidate as (
  select id from orchestrator.orchestrator_tasks
  where task_type='skill.evaluate' and payload->>'owner_id'=p_owner_id::text
   and (
    (status='pending' and available_at<=now() and attempt_count<max_attempts)
    or (status='running' and lease_expires_at<=now() and attempt_count<max_attempts)
   )
  order by available_at,created_at for update skip locked limit 1
 )
 update orchestrator.orchestrator_tasks t
 set status='running',worker_id=p_worker_id,lease_token=gen_random_uuid(),
  lease_expires_at=now()+make_interval(secs=>v_lease_seconds),
  attempt_count=t.attempt_count+1,error_message=null,started_at=coalesce(t.started_at,now())
 from candidate where t.id=candidate.id returning t.* into v_task;
 if not found then return null; end if;
 return jsonb_build_object('id',v_task.id,'task_type',v_task.task_type,'payload',v_task.payload,
  'lease_token',v_task.lease_token::text,'attempt_count',v_task.attempt_count,'max_attempts',v_task.max_attempts);
end;
$function$;

create or replace function public.register_todoist_control_target(
 p_owner_id uuid,p_todoist_task_id text,p_purpose text
) returns boolean language plpgsql security definer set search_path=''
as $function$
begin
 if auth.uid() is null or auth.uid()<>p_owner_id
  or p_todoist_task_id is null or length(btrim(p_todoist_task_id)) not between 1 and 64
  or p_purpose is null or length(btrim(p_purpose)) not between 1 and 100 then
  raise exception 'authenticated owner identity and bounded Todoist target required';
 end if;
 insert into orchestrator.todoist_control_targets(owner_id,todoist_task_id,purpose,created_by)
 values(p_owner_id,p_todoist_task_id,btrim(p_purpose),auth.uid())
 on conflict(owner_id,todoist_task_id) do update set purpose=excluded.purpose,active=true,allow_comments=true;
 return true;
end;
$function$;
revoke all on function public.register_todoist_control_target(uuid,text,text) from public,anon,authenticated,service_role;
grant execute on function public.register_todoist_control_target(uuid,text,text) to authenticated;
