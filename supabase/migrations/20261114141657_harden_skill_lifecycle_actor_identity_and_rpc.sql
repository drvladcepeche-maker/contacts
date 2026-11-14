create table orchestrator.skill_lifecycle_actor_grants (
  owner_id uuid not null references auth.users(id) on delete restrict,
  user_id uuid not null references auth.users(id) on delete restrict,
  can_review boolean not null default false,
  can_approve boolean not null default false,
  can_revoke boolean not null default false,
  granted_by uuid not null references auth.users(id) on delete restrict,
  granted_at timestamptz not null default now(),
  revoked_at timestamptz,
  primary key(owner_id,user_id),
  check(owner_id <> user_id),
  check(can_review or can_approve or can_revoke)
);
alter table orchestrator.skill_lifecycle_actor_grants enable row level security;
alter table orchestrator.skill_lifecycle_actor_grants force row level security;
revoke all on table orchestrator.skill_lifecycle_actor_grants from public,anon,authenticated,service_role;

alter function public.append_skill_lifecycle_event(uuid,text,text,text,text,text,text,text,text,text,text,text,uuid,jsonb,uuid,text,text) set schema orchestrator;
alter function orchestrator.append_skill_lifecycle_event(uuid,text,text,text,text,text,text,text,text,text,text,text,uuid,jsonb,uuid,text,text) rename to append_skill_lifecycle_event_internal;
alter function public.get_skill_lifecycle_events(uuid,text,text) set schema orchestrator;
alter function orchestrator.get_skill_lifecycle_events(uuid,text,text) rename to get_skill_lifecycle_events_internal;
drop function orchestrator.get_skill_lifecycle_events_internal(uuid,text,text);

grant usage on schema orchestrator to authenticated,service_role;
revoke usage on schema orchestrator from public,anon;

create or replace function orchestrator.append_skill_lifecycle_event_internal(
 p_owner_id uuid,p_skill_id text,p_skill_version text,p_event_type text,
 p_policy_version text,p_content_hash text,p_idempotency_key text,
 p_suite_id text default null,p_suite_version text default null,p_suite_hash text default null,
 p_report_hash text default null,p_evaluation_status text default null,
 p_related_event_id uuid default null,p_event_payload jsonb default '{}'::jsonb,
 p_queue_task_id uuid default null,p_lease_token text default null,p_actor_worker_id text default null
) returns uuid language plpgsql security definer set search_path=''
as $function$
declare
 v_actor_type text; v_actor_user_id uuid; v_previous_hash text; v_event_hash text;
 v_event_id uuid:=gen_random_uuid(); v_created_at timestamptz:=clock_timestamp();
 v_payload jsonb; v_existing orchestrator.skill_lifecycle_events%rowtype;
 v_related orchestrator.skill_lifecycle_events%rowtype;
 v_evaluation orchestrator.skill_lifecycle_events%rowtype;
begin
 if p_owner_id is null or p_skill_id is null or p_skill_id !~ '^[a-z][a-z0-9_.-]{1,127}$'
 or p_skill_version is null or p_skill_version !~ '^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-[0-9A-Za-z.-]+)?(\+[0-9A-Za-z.-]+)?$'
 or p_event_type is null or p_event_type not in ('evaluation','review','approval','rejection','revocation')
 or p_policy_version is null or length(btrim(p_policy_version)) not between 1 and 100
 or p_content_hash is null or p_content_hash !~ '^[a-f0-9]{64}$'
 or p_idempotency_key is null or length(btrim(p_idempotency_key)) not between 1 and 256
 or p_event_payload is null or jsonb_typeof(p_event_payload)<>'object'
 or octet_length(p_event_payload::text)>16384 then raise exception 'invalid skill lifecycle event'; end if;

 if orchestrator.is_service_role_context() then
  if p_event_type<>'evaluation' or p_queue_task_id is null or p_lease_token is null
   or p_actor_worker_id is null or p_actor_worker_id !~ '^[a-z0-9][a-z0-9._-]{0,99}$'
   or p_suite_id is null or p_suite_version is null or p_suite_hash is null
   or p_suite_hash !~ '^[a-f0-9]{64}$' or p_report_hash is null
   or p_report_hash !~ '^[a-f0-9]{64}$' or p_evaluation_status is null
   or p_evaluation_status not in ('pass','fail','inconclusive') or p_related_event_id is not null
  then raise exception 'worker may append evaluation events only'; end if;
  if not exists(select 1 from orchestrator.orchestrator_tasks t
   where t.id=p_queue_task_id and t.task_type='skill.evaluate' and t.status='running'
    and t.worker_id=p_actor_worker_id and t.lease_token::text=p_lease_token
    and t.lease_expires_at>clock_timestamp() and t.payload->>'owner_id'=p_owner_id::text)
  then raise exception 'stale, unowned, or incorrectly scoped skill evaluation lease'; end if;
  v_actor_type:='worker'; v_actor_user_id:=null;
 else
  v_actor_user_id:=auth.uid();
  if v_actor_user_id is null or p_event_type not in ('review','approval','rejection','revocation')
   or p_queue_task_id is not null or p_lease_token is not null or p_actor_worker_id is not null
   or p_suite_hash is null or p_suite_hash !~ '^[a-f0-9]{64}$'
   or p_report_hash is null or p_report_hash !~ '^[a-f0-9]{64}$'
  then raise exception 'authenticated human lifecycle event with exact evidence hashes required'; end if;
  if v_actor_user_id<>p_owner_id and not exists(
   select 1 from orchestrator.skill_lifecycle_actor_grants g
    where g.owner_id=p_owner_id and g.user_id=v_actor_user_id and g.revoked_at is null
     and case p_event_type when 'review' then g.can_review when 'approval' then g.can_approve
       when 'rejection' then g.can_review when 'revocation' then g.can_revoke else false end)
  then raise exception 'human actor is not authorized for this lifecycle action'; end if;
  v_actor_type:='human';
 end if;

 perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(p_owner_id::text||':'||p_skill_id||':'||p_skill_version,0));
 select * into v_existing from orchestrator.skill_lifecycle_events
  where owner_id=p_owner_id and idempotency_key=p_idempotency_key;
 if found then
  if v_existing.skill_id=p_skill_id and v_existing.skill_version=p_skill_version
   and v_existing.event_type=p_event_type and v_existing.content_hash=p_content_hash
   and v_existing.report_hash is not distinct from p_report_hash
   and v_existing.suite_hash is not distinct from p_suite_hash
   and v_existing.related_event_id is not distinct from p_related_event_id
   and v_existing.actor_user_id is not distinct from v_actor_user_id
   and v_existing.actor_worker_id is not distinct from p_actor_worker_id then return v_existing.id; end if;
  raise exception 'idempotency key reused with different lifecycle event';
 end if;

 if p_event_type='review' then
  select * into v_evaluation from orchestrator.skill_lifecycle_events
   where owner_id=p_owner_id and skill_id=p_skill_id and skill_version=p_skill_version
    and event_type='evaluation' and content_hash=p_content_hash and suite_hash=p_suite_hash
    and report_hash=p_report_hash and evaluation_status='pass'
   order by created_at desc,id desc limit 1;
  if not found then raise exception 'review requires a passing evaluation for the exact content, suite and report'; end if;
 elsif p_event_type='approval' then
  select * into v_related from orchestrator.skill_lifecycle_events
   where id=p_related_event_id and owner_id=p_owner_id and skill_id=p_skill_id
    and skill_version=p_skill_version and event_type='review' and content_hash=p_content_hash
    and suite_hash=p_suite_hash and report_hash=p_report_hash and actor_user_id<>v_actor_user_id;
  if not found then raise exception 'approval requires a matching human review by a different authorized user'; end if;
  if exists(select 1 from orchestrator.skill_lifecycle_events where owner_id=p_owner_id
   and skill_id=p_skill_id and skill_version=p_skill_version and event_type='approval'
   and content_hash=p_content_hash) then raise exception 'this exact skill revision already has an approval event'; end if;
 elsif p_event_type='rejection' then
  if not exists(select 1 from orchestrator.skill_lifecycle_events where id=p_related_event_id
   and owner_id=p_owner_id and skill_id=p_skill_id and skill_version=p_skill_version
   and event_type='review' and content_hash=p_content_hash and suite_hash=p_suite_hash
   and report_hash=p_report_hash) then raise exception 'rejection requires a matching review event'; end if;
 elsif p_event_type='revocation' then
  select * into v_related from orchestrator.skill_lifecycle_events where id=p_related_event_id
   and owner_id=p_owner_id and skill_id=p_skill_id and skill_version=p_skill_version
   and event_type='approval' and content_hash=p_content_hash
   and suite_hash=p_suite_hash and report_hash=p_report_hash;
  if not found then raise exception 'revocation requires a matching approval event'; end if;
  if exists(select 1 from orchestrator.skill_lifecycle_events where owner_id=p_owner_id
   and related_event_id=p_related_event_id and event_type='revocation')
  then raise exception 'approval has already been revoked'; end if;
 end if;

 select event_hash into v_previous_hash from orchestrator.skill_lifecycle_events
  where owner_id=p_owner_id and skill_id=p_skill_id and skill_version=p_skill_version
  order by created_at desc,id desc limit 1;
 v_payload:=jsonb_build_object('id',v_event_id,'owner_id',p_owner_id,'skill_id',p_skill_id,
  'skill_version',p_skill_version,'event_type',p_event_type,'actor_type',v_actor_type,
  'actor_user_id',v_actor_user_id,'actor_worker_id',case when v_actor_type='worker' then p_actor_worker_id else null end,
  'queue_task_id',case when v_actor_type='worker' then p_queue_task_id else null end,
  'policy_version',p_policy_version,'content_hash',p_content_hash,'suite_id',p_suite_id,
  'suite_version',p_suite_version,'suite_hash',p_suite_hash,'report_hash',p_report_hash,
  'evaluation_status',p_evaluation_status,'related_event_id',p_related_event_id,
  'idempotency_key',p_idempotency_key,'event_payload',p_event_payload,
  'previous_event_hash',v_previous_hash,'created_at',v_created_at);
 v_event_hash:=pg_catalog.encode(extensions.digest(pg_catalog.convert_to(v_payload::text,'UTF8'),'sha256'),'hex');
 insert into orchestrator.skill_lifecycle_events(id,owner_id,skill_id,skill_version,event_type,
  actor_type,actor_user_id,actor_worker_id,queue_task_id,policy_version,content_hash,suite_id,
  suite_version,suite_hash,report_hash,evaluation_status,related_event_id,idempotency_key,
  event_payload,previous_event_hash,event_hash,created_at)
 values(v_event_id,p_owner_id,p_skill_id,p_skill_version,p_event_type,v_actor_type,v_actor_user_id,
  case when v_actor_type='worker' then p_actor_worker_id else null end,
  case when v_actor_type='worker' then p_queue_task_id else null end,p_policy_version,p_content_hash,
  p_suite_id,p_suite_version,p_suite_hash,p_report_hash,p_evaluation_status,p_related_event_id,
  p_idempotency_key,p_event_payload,v_previous_hash,v_event_hash,v_created_at);
 return v_event_id;
end;
$function$;

create or replace function orchestrator.get_skill_lifecycle_events_internal(p_owner_id uuid,p_skill_id text,p_skill_version text default null)
returns jsonb language plpgsql security definer set search_path=''
as $function$
begin
 if not orchestrator.is_service_role_context() and (auth.uid() is null or (
  auth.uid()<>p_owner_id and not exists(select 1 from orchestrator.skill_lifecycle_actor_grants g
   where g.owner_id=p_owner_id and g.user_id=auth.uid() and g.revoked_at is null)))
 then raise exception 'authenticated owner or granted reviewer identity required'; end if;
 return coalesce((select jsonb_agg(to_jsonb(e) order by e.created_at,e.id)
  from orchestrator.skill_lifecycle_events e where e.owner_id=p_owner_id and e.skill_id=p_skill_id
   and (p_skill_version is null or e.skill_version=p_skill_version)),'[]'::jsonb);
end;
$function$;

create or replace function orchestrator.grant_skill_lifecycle_actor_internal(
 p_owner_id uuid,p_user_id uuid,p_can_review boolean,p_can_approve boolean,p_can_revoke boolean
) returns boolean language plpgsql security definer set search_path=''
as $function$
begin
 if auth.uid() is null or auth.uid()<>p_owner_id or p_user_id is null or p_user_id=p_owner_id
  or not(coalesce(p_can_review,false) or coalesce(p_can_approve,false) or coalesce(p_can_revoke,false))
 then raise exception 'only the owner can grant a non-owner lifecycle actor'; end if;
 insert into orchestrator.skill_lifecycle_actor_grants(owner_id,user_id,can_review,can_approve,can_revoke,granted_by,granted_at,revoked_at)
 values(p_owner_id,p_user_id,coalesce(p_can_review,false),coalesce(p_can_approve,false),coalesce(p_can_revoke,false),auth.uid(),now(),null)
 on conflict(owner_id,user_id) do update set can_review=excluded.can_review,can_approve=excluded.can_approve,
  can_revoke=excluded.can_revoke,granted_by=excluded.granted_by,granted_at=now(),revoked_at=null;
 return true;
end;
$function$;

create or replace function orchestrator.verify_skill_lifecycle_chain_internal(p_owner_id uuid,p_skill_id text,p_skill_version text)
returns jsonb language plpgsql security definer set search_path=''
as $function$
declare r orchestrator.skill_lifecycle_events%rowtype; v_previous text:=null;
 v_expected text; v_payload jsonb; v_count integer:=0;
begin
 if not orchestrator.is_service_role_context() and (auth.uid() is null or (
  auth.uid()<>p_owner_id and not exists(select 1 from orchestrator.skill_lifecycle_actor_grants g
   where g.owner_id=p_owner_id and g.user_id=auth.uid() and g.revoked_at is null)))
 then raise exception 'authenticated owner or granted reviewer identity required'; end if;
 for r in select * from orchestrator.skill_lifecycle_events where owner_id=p_owner_id
  and skill_id=p_skill_id and skill_version=p_skill_version order by created_at,id
 loop
  v_count:=v_count+1;
  if r.previous_event_hash is distinct from v_previous then
   return jsonb_build_object('valid',false,'events_checked',v_count,'broken_event_id',r.id,'reason','previous_hash_mismatch'); end if;
  v_payload:=jsonb_build_object('id',r.id,'owner_id',r.owner_id,'skill_id',r.skill_id,
   'skill_version',r.skill_version,'event_type',r.event_type,'actor_type',r.actor_type,
   'actor_user_id',r.actor_user_id,'actor_worker_id',r.actor_worker_id,'queue_task_id',r.queue_task_id,
   'policy_version',r.policy_version,'content_hash',r.content_hash,'suite_id',r.suite_id,
   'suite_version',r.suite_version,'suite_hash',r.suite_hash,'report_hash',r.report_hash,
   'evaluation_status',r.evaluation_status,'related_event_id',r.related_event_id,
   'idempotency_key',r.idempotency_key,'event_payload',r.event_payload,
   'previous_event_hash',r.previous_event_hash,'created_at',r.created_at);
  v_expected:=pg_catalog.encode(extensions.digest(pg_catalog.convert_to(v_payload::text,'UTF8'),'sha256'),'hex');
  if r.event_hash<>v_expected then
   return jsonb_build_object('valid',false,'events_checked',v_count,'broken_event_id',r.id,'reason','event_hash_mismatch'); end if;
  v_previous:=r.event_hash;
 end loop;
 return jsonb_build_object('valid',true,'events_checked',v_count,'broken_event_id',null);
end;
$function$;

revoke all on function orchestrator.append_skill_lifecycle_event_internal(uuid,text,text,text,text,text,text,text,text,text,text,text,uuid,jsonb,uuid,text,text) from public,anon,authenticated,service_role;
revoke all on function orchestrator.get_skill_lifecycle_events_internal(uuid,text,text) from public,anon,authenticated,service_role;
revoke all on function orchestrator.grant_skill_lifecycle_actor_internal(uuid,uuid,boolean,boolean,boolean) from public,anon,authenticated,service_role;
revoke all on function orchestrator.verify_skill_lifecycle_chain_internal(uuid,text,text) from public,anon,authenticated,service_role;
grant execute on function orchestrator.append_skill_lifecycle_event_internal(uuid,text,text,text,text,text,text,text,text,text,text,text,uuid,jsonb,uuid,text,text) to authenticated,service_role;
grant execute on function orchestrator.get_skill_lifecycle_events_internal(uuid,text,text) to authenticated,service_role;
grant execute on function orchestrator.grant_skill_lifecycle_actor_internal(uuid,uuid,boolean,boolean,boolean) to authenticated;
grant execute on function orchestrator.verify_skill_lifecycle_chain_internal(uuid,text,text) to authenticated,service_role;

create or replace function public.append_skill_lifecycle_event(
 p_owner_id uuid,p_skill_id text,p_skill_version text,p_event_type text,p_policy_version text,
 p_content_hash text,p_idempotency_key text,p_suite_id text default null,p_suite_version text default null,
 p_suite_hash text default null,p_report_hash text default null,p_evaluation_status text default null,
 p_related_event_id uuid default null,p_event_payload jsonb default '{}'::jsonb,p_queue_task_id uuid default null,
 p_lease_token text default null,p_actor_worker_id text default null
) returns uuid language sql security invoker set search_path=''
as $function$ select orchestrator.append_skill_lifecycle_event_internal(
 p_owner_id,p_skill_id,p_skill_version,p_event_type,p_policy_version,p_content_hash,p_idempotency_key,
 p_suite_id,p_suite_version,p_suite_hash,p_report_hash,p_evaluation_status,p_related_event_id,
 p_event_payload,p_queue_task_id,p_lease_token,p_actor_worker_id); $function$;

create or replace function public.get_skill_lifecycle_events(p_owner_id uuid,p_skill_id text,p_skill_version text default null)
returns jsonb language sql security invoker set search_path=''
as $function$ select orchestrator.get_skill_lifecycle_events_internal(p_owner_id,p_skill_id,p_skill_version); $function$;

create or replace function public.grant_skill_lifecycle_actor(
 p_owner_id uuid,p_user_id uuid,p_can_review boolean default false,p_can_approve boolean default false,p_can_revoke boolean default false
) returns boolean language sql security invoker set search_path=''
as $function$ select orchestrator.grant_skill_lifecycle_actor_internal(p_owner_id,p_user_id,p_can_review,p_can_approve,p_can_revoke); $function$;

create or replace function public.verify_skill_lifecycle_chain(p_owner_id uuid,p_skill_id text,p_skill_version text)
returns jsonb language sql security invoker set search_path=''
as $function$ select orchestrator.verify_skill_lifecycle_chain_internal(p_owner_id,p_skill_id,p_skill_version); $function$;

revoke all on function public.append_skill_lifecycle_event(uuid,text,text,text,text,text,text,text,text,text,text,text,uuid,jsonb,uuid,text,text) from public,anon,authenticated,service_role;
revoke all on function public.get_skill_lifecycle_events(uuid,text,text) from public,anon,authenticated,service_role;
revoke all on function public.grant_skill_lifecycle_actor(uuid,uuid,boolean,boolean,boolean) from public,anon,authenticated,service_role;
revoke all on function public.verify_skill_lifecycle_chain(uuid,text,text) from public,anon,authenticated,service_role;
grant execute on function public.append_skill_lifecycle_event(uuid,text,text,text,text,text,text,text,text,text,text,text,uuid,jsonb,uuid,text,text) to authenticated,service_role;
grant execute on function public.get_skill_lifecycle_events(uuid,text,text) to authenticated,service_role;
grant execute on function public.grant_skill_lifecycle_actor(uuid,uuid,boolean,boolean,boolean) to authenticated;
grant execute on function public.verify_skill_lifecycle_chain(uuid,text,text) to authenticated,service_role;
