
alter table orchestrator.worker_credentials
 add column owner_id uuid references auth.users(id) on delete restrict;

create index worker_credentials_owner_worker_idx
 on orchestrator.worker_credentials(owner_id,worker_id,expires_at desc);

alter table orchestrator.worker_credentials
 add constraint worker_credentials_skill_evaluator_scope_check
 check (
  worker_id <> 'osiris-skill-evaluator'
  or (
   owner_id is not null
   and allowed_task_types = array['skill.evaluate']::text[]
   and scopes = array['queue.claim','queue.complete','queue.renew','queue.fail','skill.evaluation.append']::text[]
  )
 );

create or replace function orchestrator.claim_skill_evaluation_task_scoped(
 p_worker_id text,p_owner_id uuid,p_lease_seconds integer
) returns jsonb language plpgsql security definer set search_path=''
as $function$
declare v_task orchestrator.orchestrator_tasks%rowtype;
 v_lease_seconds integer:=least(greatest(coalesce(p_lease_seconds,120),30),300);
begin
 if p_worker_id<>'osiris-skill-evaluator' or p_owner_id is null then
  raise exception 'invalid skill evaluator worker identity';
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
  order by available_at,created_at
  for update skip locked limit 1
 )
 update orchestrator.orchestrator_tasks t
 set status='running',worker_id=p_worker_id,lease_token=gen_random_uuid(),
  lease_expires_at=now()+make_interval(secs=>v_lease_seconds),
  attempt_count=t.attempt_count+1,error_message=null,started_at=coalesce(t.started_at,now())
 from candidate where t.id=candidate.id
 returning t.* into v_task;

 if not found then return null; end if;
 return jsonb_build_object('id',v_task.id,'task_type',v_task.task_type,'payload',v_task.payload,
  'lease_token',v_task.lease_token::text,'attempt_count',v_task.attempt_count,'max_attempts',v_task.max_attempts);
end;
$function$;

revoke all on function orchestrator.claim_skill_evaluation_task_scoped(text,uuid,integer)
 from public,anon,authenticated;
grant execute on function orchestrator.claim_skill_evaluation_task_scoped(text,uuid,integer) to service_role;

create or replace function orchestrator.issue_skill_evaluation_worker_credential_internal(p_owner_id uuid)
returns text language plpgsql security definer set search_path=''
as $function$
declare v_token text; v_hash text; v_prefix text; v_now timestamptz:=clock_timestamp();
begin
 if auth.uid() is null or auth.uid()<>p_owner_id then
  raise exception 'authenticated project owner identity required';
 end if;
 update orchestrator.worker_credentials
 set revoked_at=v_now
 where owner_id=p_owner_id and worker_id='osiris-skill-evaluator' and revoked_at is null;

 v_token:='orch_'||rtrim(translate(encode(gen_random_bytes(48),'base64'),'+/','-_'),'=');
 v_hash:=pg_catalog.encode(extensions.digest(pg_catalog.convert_to(v_token,'UTF8'),'sha256'),'hex');
 v_prefix:=left(v_token,16);
 insert into orchestrator.worker_credentials(
  owner_id,worker_id,token_hash,token_prefix,scopes,allowed_task_types,created_at,expires_at
 ) values(
  p_owner_id,'osiris-skill-evaluator',v_hash,v_prefix,
  array['queue.claim','queue.complete','queue.renew','queue.fail','skill.evaluation.append']::text[],
  array['skill.evaluate']::text[],v_now,v_now+interval '48 hours'
 );
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
 where owner_id=p_owner_id and worker_id='osiris-skill-evaluator' and revoked_at is null;
 get diagnostics v_count=row_count;
 return v_count;
end;
$function$;

revoke all on function orchestrator.issue_skill_evaluation_worker_credential_internal(uuid) from public,anon,authenticated,service_role;
revoke all on function orchestrator.revoke_skill_evaluation_worker_credential_internal(uuid) from public,anon,authenticated,service_role;

create or replace function public.issue_skill_evaluation_worker_credential(p_owner_id uuid)
returns text language sql security definer set search_path=''
as $function$ select orchestrator.issue_skill_evaluation_worker_credential_internal(p_owner_id); $function$;
create or replace function public.revoke_skill_evaluation_worker_credential(p_owner_id uuid)
returns integer language sql security definer set search_path=''
as $function$ select orchestrator.revoke_skill_evaluation_worker_credential_internal(p_owner_id); $function$;

revoke all on function public.issue_skill_evaluation_worker_credential(uuid) from public,anon,authenticated,service_role;
revoke all on function public.revoke_skill_evaluation_worker_credential(uuid) from public,anon,authenticated,service_role;
grant execute on function public.issue_skill_evaluation_worker_credential(uuid) to authenticated;
grant execute on function public.revoke_skill_evaluation_worker_credential(uuid) to authenticated;
