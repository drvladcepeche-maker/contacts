
-- Immutable policy definitions and append-only control-plane audit.
create table orchestrator.skill_lifecycle_policy_registry (
  owner_id uuid not null references auth.users(id) on delete restrict,
  policy_version text not null check (length(btrim(policy_version)) between 1 and 100),
  policy_hash text not null check (policy_hash ~ '^[a-f0-9]{64}$'),
  policy_document jsonb not null check (
    jsonb_typeof(policy_document) = 'object'
    and octet_length(policy_document::text) <= 32768
  ),
  registered_by uuid not null references auth.users(id) on delete restrict,
  registered_at timestamptz not null default clock_timestamp(),
  primary key(owner_id, policy_version),
  unique(owner_id, policy_hash)
);
alter table orchestrator.skill_lifecycle_policy_registry enable row level security;
alter table orchestrator.skill_lifecycle_policy_registry force row level security;
revoke all on table orchestrator.skill_lifecycle_policy_registry from public,anon,authenticated,service_role;

create table orchestrator.skill_lifecycle_policy_events (
  id uuid primary key default gen_random_uuid(),
  owner_id uuid not null references auth.users(id) on delete restrict,
  policy_version text not null,
  policy_hash text not null check (policy_hash ~ '^[a-f0-9]{64}$'),
  event_type text not null check (event_type in ('registered','revoked')),
  actor_user_id uuid not null references auth.users(id) on delete restrict,
  reason text not null,
  previous_event_hash text check (previous_event_hash is null or previous_event_hash ~ '^[a-f0-9]{64}$'),
  event_hash text not null unique check (event_hash ~ '^[a-f0-9]{64}$'),
  created_at timestamptz not null default clock_timestamp(),
  unique(owner_id, policy_version, event_type)
);
alter table orchestrator.skill_lifecycle_policy_events enable row level security;
alter table orchestrator.skill_lifecycle_policy_events force row level security;
revoke all on table orchestrator.skill_lifecycle_policy_events from public,anon,authenticated,service_role;

create table orchestrator.skill_lifecycle_actor_grant_events (
  id uuid primary key default gen_random_uuid(),
  owner_id uuid not null references auth.users(id) on delete restrict,
  subject_user_id uuid not null references auth.users(id) on delete restrict,
  event_type text not null check (event_type in ('granted','updated','revoked')),
  can_review boolean not null,
  can_approve boolean not null,
  can_revoke boolean not null,
  actor_user_id uuid not null references auth.users(id) on delete restrict,
  previous_event_hash text check (previous_event_hash is null or previous_event_hash ~ '^[a-f0-9]{64}$'),
  event_hash text not null unique check (event_hash ~ '^[a-f0-9]{64}$'),
  created_at timestamptz not null default clock_timestamp(),
  check(owner_id <> subject_user_id)
);
alter table orchestrator.skill_lifecycle_actor_grant_events enable row level security;
alter table orchestrator.skill_lifecycle_actor_grant_events force row level security;
revoke all on table orchestrator.skill_lifecycle_actor_grant_events from public,anon,authenticated,service_role;

create index skill_lifecycle_policy_events_owner_idx
 on orchestrator.skill_lifecycle_policy_events(owner_id, created_at, id);
create index skill_lifecycle_actor_grant_events_owner_idx
 on orchestrator.skill_lifecycle_actor_grant_events(owner_id, created_at, id);

create trigger skill_lifecycle_policy_registry_immutable
before update or delete on orchestrator.skill_lifecycle_policy_registry
for each row execute function orchestrator.reject_skill_lifecycle_mutation();
create trigger skill_lifecycle_policy_registry_no_truncate
before truncate on orchestrator.skill_lifecycle_policy_registry
for each statement execute function orchestrator.reject_skill_lifecycle_mutation();
create trigger skill_lifecycle_policy_events_immutable
before update or delete on orchestrator.skill_lifecycle_policy_events
for each row execute function orchestrator.reject_skill_lifecycle_mutation();
create trigger skill_lifecycle_policy_events_no_truncate
before truncate on orchestrator.skill_lifecycle_policy_events
for each statement execute function orchestrator.reject_skill_lifecycle_mutation();
create trigger skill_lifecycle_actor_grant_events_immutable
before update or delete on orchestrator.skill_lifecycle_actor_grant_events
for each row execute function orchestrator.reject_skill_lifecycle_mutation();
create trigger skill_lifecycle_actor_grant_events_no_truncate
before truncate on orchestrator.skill_lifecycle_actor_grant_events
for each statement execute function orchestrator.reject_skill_lifecycle_mutation();

alter table orchestrator.skill_lifecycle_events
 add column policy_hash text;
create or replace function orchestrator.bind_skill_lifecycle_policy_hash()
returns trigger language plpgsql security definer set search_path=''
as $function$
declare v_hash text;
begin
 select r.policy_hash into v_hash
 from orchestrator.skill_lifecycle_policy_registry r
 where r.owner_id=new.owner_id and r.policy_version=new.policy_version;
 if not found or exists(
   select 1 from orchestrator.skill_lifecycle_policy_events e
   where e.owner_id=new.owner_id and e.policy_version=new.policy_version and e.event_type='revoked'
 ) then
   raise exception 'unknown or revoked skill lifecycle policy version';
 end if;
 if new.event_payload->>'policy_hash' is distinct from v_hash then
   raise exception 'skill lifecycle event policy hash does not match the active immutable policy';
 end if;
 new.policy_hash:=v_hash;
 return new;
end;
$function$;
revoke all on function orchestrator.bind_skill_lifecycle_policy_hash() from public,anon,authenticated,service_role;
create trigger skill_lifecycle_event_policy_binding
before insert on orchestrator.skill_lifecycle_events
for each row execute function orchestrator.bind_skill_lifecycle_policy_hash();
alter table orchestrator.skill_lifecycle_events
 add constraint skill_lifecycle_events_policy_hash_check
 check (policy_hash ~ '^[a-f0-9]{64}$');

-- Seed the first immutable policy for the existing project owner.
do $seed$
declare
 v_owner uuid := '690e2132-b3b8-4594-84f9-8c2b55b0d527';
 v_version text := '1.0.0';
 v_doc jsonb := '{
   "policy_id":"osiris.skill-lifecycle",
   "policy_version":"1.0.0",
   "purpose":"Govern skill evaluation, human review, approval, rejection and revocation.",
   "evaluation":{"required_status":"pass","required_system_gates":["purpose_alignment","reference_coverage","dangerous_instruction_scan","placeholder_scan","step_quality","capability_boundary"],"suite_and_report_hashes_required":true},
   "approval":{"four_eyes":true,"reviewer_must_differ_from_approver":true,"allowed_risk_levels":["low","normal"],"capability_hints_allowed":false},
   "worker":{"allowed_task_types":["skill.evaluate"],"requires_active_queue_lease":true,"may_append_evaluation_only":true},
   "execution":{"skill_execution_allowed":false,"capability_grants_allowed":false,"production_autonomy_enabled":false},
   "resolver":{"connected":false,"rollout_mode":"dry-run-after-gates"}
 }'::jsonb;
 v_hash text;
 v_event_id uuid := gen_random_uuid();
 v_created timestamptz := clock_timestamp();
 v_payload jsonb;
 v_event_hash text;
begin
 if not exists(select 1 from auth.users where id=v_owner) then
   raise exception 'policy owner identity not found';
 end if;
 v_hash:=pg_catalog.encode(extensions.digest(pg_catalog.convert_to(v_doc::text,'UTF8'),'sha256'),'hex');
 insert into orchestrator.skill_lifecycle_policy_registry(owner_id,policy_version,policy_hash,policy_document,registered_by,registered_at)
 values(v_owner,v_version,v_hash,v_doc,v_owner,v_created);
 v_payload:=jsonb_build_object('id',v_event_id,'owner_id',v_owner,'policy_version',v_version,
   'policy_hash',v_hash,'event_type','registered','actor_user_id',v_owner,
   'reason','Initial immutable Skill Lifecycle policy seed','previous_event_hash',null,'created_at',v_created);
 v_event_hash:=pg_catalog.encode(extensions.digest(pg_catalog.convert_to(v_payload::text,'UTF8'),'sha256'),'hex');
 insert into orchestrator.skill_lifecycle_policy_events(id,owner_id,policy_version,policy_hash,event_type,actor_user_id,reason,previous_event_hash,event_hash,created_at)
 values(v_event_id,v_owner,v_version,v_hash,'registered',v_owner,'Initial immutable Skill Lifecycle policy seed',null,v_event_hash,v_created);
end;
$seed$;

-- The original append routine remains the guarded writer; the wrapper injects the immutable policy hash into the hashed event payload.
alter function orchestrator.append_skill_lifecycle_event_internal(uuid,text,text,text,text,text,text,text,text,text,text,text,uuid,jsonb,uuid,text,text)
 rename to append_skill_lifecycle_event_unbound_internal;

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
 v_policy_hash text;
 v_existing orchestrator.skill_lifecycle_events%rowtype;
begin
 select r.policy_hash into v_policy_hash
 from orchestrator.skill_lifecycle_policy_registry r
 where r.owner_id=p_owner_id and r.policy_version=p_policy_version;
 if not found or exists(select 1 from orchestrator.skill_lifecycle_policy_events e
   where e.owner_id=p_owner_id and e.policy_version=p_policy_version and e.event_type='revoked') then
   raise exception 'unknown or revoked skill lifecycle policy version';
 end if;
 if p_event_payload is null or jsonb_typeof(p_event_payload)<>'object'
   or octet_length(p_event_payload::text)>16384 then
   raise exception 'invalid skill lifecycle event payload';
 end if;
 if p_event_payload ? 'policy_hash' and p_event_payload->>'policy_hash' is distinct from v_policy_hash then
   raise exception 'caller supplied a mismatched policy hash';
 end if;
 select * into v_existing from orchestrator.skill_lifecycle_events
 where owner_id=p_owner_id and idempotency_key=p_idempotency_key;
 if found and (v_existing.policy_version<>p_policy_version or v_existing.policy_hash<>v_policy_hash) then
   raise exception 'idempotency key cannot replay an event under a different policy version';
 end if;
 return orchestrator.append_skill_lifecycle_event_unbound_internal(
  p_owner_id,p_skill_id,p_skill_version,p_event_type,p_policy_version,p_content_hash,p_idempotency_key,
  p_suite_id,p_suite_version,p_suite_hash,p_report_hash,p_evaluation_status,p_related_event_id,
  p_event_payload || jsonb_build_object('policy_hash',v_policy_hash),
  p_queue_task_id,p_lease_token,p_actor_worker_id
 );
end;
$function$;

revoke all on function orchestrator.append_skill_lifecycle_event_unbound_internal(uuid,text,text,text,text,text,text,text,text,text,text,text,uuid,jsonb,uuid,text,text)
 from public,anon,authenticated,service_role;
revoke all on function orchestrator.append_skill_lifecycle_event_internal(uuid,text,text,text,text,text,text,text,text,text,text,text,uuid,jsonb,uuid,text,text)
 from public,anon,authenticated,service_role;
grant execute on function orchestrator.append_skill_lifecycle_event_internal(uuid,text,text,text,text,text,text,text,text,text,text,text,uuid,jsonb,uuid,text,text)
 to authenticated,service_role;

create or replace function public.append_skill_lifecycle_event(
 p_owner_id uuid,p_skill_id text,p_skill_version text,p_event_type text,p_policy_version text,
 p_content_hash text,p_idempotency_key text,p_suite_id text default null,p_suite_version text default null,
 p_suite_hash text default null,p_report_hash text default null,p_evaluation_status text default null,
 p_related_event_id uuid default null,p_event_payload jsonb default '{}'::jsonb,p_queue_task_id uuid default null,
 p_lease_token text default null,p_actor_worker_id text default null
) returns uuid language plpgsql security definer set search_path=''
as $function$
begin
 return orchestrator.append_skill_lifecycle_event_internal(
  p_owner_id,p_skill_id,p_skill_version,p_event_type,p_policy_version,p_content_hash,p_idempotency_key,
  p_suite_id,p_suite_version,p_suite_hash,p_report_hash,p_evaluation_status,p_related_event_id,
  p_event_payload,p_queue_task_id,p_lease_token,p_actor_worker_id);
end;
$function$;
revoke all on function public.append_skill_lifecycle_event(uuid,text,text,text,text,text,text,text,text,text,text,text,uuid,jsonb,uuid,text,text)
 from public,anon,authenticated,service_role;
grant execute on function public.append_skill_lifecycle_event(uuid,text,text,text,text,text,text,text,text,text,text,text,uuid,jsonb,uuid,text,text)
 to authenticated,service_role;

create or replace function orchestrator.register_skill_lifecycle_policy_internal(
 p_owner_id uuid,p_policy_version text,p_policy_document jsonb
) returns text language plpgsql security definer set search_path=''
as $function$
declare v_hash text; v_existing orchestrator.skill_lifecycle_policy_registry%rowtype;
 v_prev text; v_id uuid:=gen_random_uuid(); v_created timestamptz:=clock_timestamp(); v_payload jsonb; v_event_hash text;
begin
 if auth.uid() is null or auth.uid()<>p_owner_id or p_policy_version is null
  or length(btrim(p_policy_version)) not between 1 and 100
  or p_policy_document is null or jsonb_typeof(p_policy_document)<>'object'
  or octet_length(p_policy_document::text)>32768 then
  raise exception 'only the authenticated owner may register a bounded policy document';
 end if;
 v_hash:=pg_catalog.encode(extensions.digest(pg_catalog.convert_to(p_policy_document::text,'UTF8'),'sha256'),'hex');
 select * into v_existing from orchestrator.skill_lifecycle_policy_registry
  where owner_id=p_owner_id and policy_version=p_policy_version;
 if found then
  if v_existing.policy_hash=v_hash then return v_hash; end if;
  raise exception 'policy version is immutable and already bound to different content';
 end if;
 insert into orchestrator.skill_lifecycle_policy_registry(owner_id,policy_version,policy_hash,policy_document,registered_by,registered_at)
 values(p_owner_id,p_policy_version,v_hash,p_policy_document,auth.uid(),v_created);
 select event_hash into v_prev from orchestrator.skill_lifecycle_policy_events
  where owner_id=p_owner_id order by created_at desc,id desc limit 1;
 v_payload:=jsonb_build_object('id',v_id,'owner_id',p_owner_id,'policy_version',p_policy_version,
  'policy_hash',v_hash,'event_type','registered','actor_user_id',auth.uid(),
  'reason','Owner registered immutable policy version','previous_event_hash',v_prev,'created_at',v_created);
 v_event_hash:=pg_catalog.encode(extensions.digest(pg_catalog.convert_to(v_payload::text,'UTF8'),'sha256'),'hex');
 insert into orchestrator.skill_lifecycle_policy_events(id,owner_id,policy_version,policy_hash,event_type,actor_user_id,reason,previous_event_hash,event_hash,created_at)
 values(v_id,p_owner_id,p_policy_version,v_hash,'registered',auth.uid(),'Owner registered immutable policy version',v_prev,v_event_hash,v_created);
 return v_hash;
end;
$function$;

create or replace function orchestrator.revoke_skill_lifecycle_policy_internal(
 p_owner_id uuid,p_policy_version text,p_reason text
) returns boolean language plpgsql security definer set search_path=''
as $function$
declare v_hash text; v_prev text; v_id uuid:=gen_random_uuid(); v_created timestamptz:=clock_timestamp();
 v_payload jsonb; v_event_hash text;
begin
 if auth.uid() is null or auth.uid()<>p_owner_id or p_reason is null or length(btrim(p_reason)) not between 1 and 1000 then
  raise exception 'only the authenticated owner may revoke a policy with a reason';
 end if;
 select policy_hash into v_hash from orchestrator.skill_lifecycle_policy_registry
  where owner_id=p_owner_id and policy_version=p_policy_version;
 if not found then raise exception 'policy version does not exist'; end if;
 if exists(select 1 from orchestrator.skill_lifecycle_policy_events
  where owner_id=p_owner_id and policy_version=p_policy_version and event_type='revoked') then return true; end if;
 select event_hash into v_prev from orchestrator.skill_lifecycle_policy_events
  where owner_id=p_owner_id order by created_at desc,id desc limit 1;
 v_payload:=jsonb_build_object('id',v_id,'owner_id',p_owner_id,'policy_version',p_policy_version,
  'policy_hash',v_hash,'event_type','revoked','actor_user_id',auth.uid(),
  'reason',btrim(p_reason),'previous_event_hash',v_prev,'created_at',v_created);
 v_event_hash:=pg_catalog.encode(extensions.digest(pg_catalog.convert_to(v_payload::text,'UTF8'),'sha256'),'hex');
 insert into orchestrator.skill_lifecycle_policy_events(id,owner_id,policy_version,policy_hash,event_type,actor_user_id,reason,previous_event_hash,event_hash,created_at)
 values(v_id,p_owner_id,p_policy_version,v_hash,'revoked',auth.uid(),btrim(p_reason),v_prev,v_event_hash,v_created);
 return true;
end;
$function$;

create or replace function public.register_skill_lifecycle_policy(p_owner_id uuid,p_policy_version text,p_policy_document jsonb)
returns text language sql security invoker set search_path=''
as $function$ select orchestrator.register_skill_lifecycle_policy_internal(p_owner_id,p_policy_version,p_policy_document); $function$;
create or replace function public.revoke_skill_lifecycle_policy(p_owner_id uuid,p_policy_version text,p_reason text)
returns boolean language sql security invoker set search_path=''
as $function$ select orchestrator.revoke_skill_lifecycle_policy_internal(p_owner_id,p_policy_version,p_reason); $function$;

revoke all on function orchestrator.register_skill_lifecycle_policy_internal(uuid,text,jsonb) from public,anon,authenticated,service_role;
revoke all on function orchestrator.revoke_skill_lifecycle_policy_internal(uuid,text,text) from public,anon,authenticated,service_role;
grant execute on function orchestrator.register_skill_lifecycle_policy_internal(uuid,text,jsonb) to authenticated;
grant execute on function orchestrator.revoke_skill_lifecycle_policy_internal(uuid,text,text) to authenticated;
revoke all on function public.register_skill_lifecycle_policy(uuid,text,jsonb) from public,anon,authenticated,service_role;
revoke all on function public.revoke_skill_lifecycle_policy(uuid,text,text) from public,anon,authenticated,service_role;
grant execute on function public.register_skill_lifecycle_policy(uuid,text,jsonb) to authenticated;
grant execute on function public.revoke_skill_lifecycle_policy(uuid,text,text) to authenticated;

-- Replace grant/revoke routines so every control-plane change is atomically audited.
create or replace function orchestrator.grant_skill_lifecycle_actor_internal(
 p_owner_id uuid,p_user_id uuid,p_can_review boolean,p_can_approve boolean,p_can_revoke boolean
) returns boolean language plpgsql security definer set search_path=''
as $function$
declare v_old orchestrator.skill_lifecycle_actor_grants%rowtype;
 v_type text; v_prev text; v_id uuid:=gen_random_uuid(); v_created timestamptz:=clock_timestamp();
 v_payload jsonb; v_event_hash text;
begin
 if auth.uid() is null or auth.uid()<>p_owner_id or p_user_id is null or p_user_id=p_owner_id
  or not(coalesce(p_can_review,false) or coalesce(p_can_approve,false) or coalesce(p_can_revoke,false))
  or not exists(select 1 from auth.users where id=p_user_id) then
  raise exception 'only the authenticated owner may grant valid non-owner lifecycle actors';
 end if;
 select * into v_old from orchestrator.skill_lifecycle_actor_grants
  where owner_id=p_owner_id and user_id=p_user_id;
 if found and v_old.revoked_at is null
  and v_old.can_review=coalesce(p_can_review,false)
  and v_old.can_approve=coalesce(p_can_approve,false)
  and v_old.can_revoke=coalesce(p_can_revoke,false) then return true; end if;
 v_type:=case when not found then 'granted' else 'updated' end;
 insert into orchestrator.skill_lifecycle_actor_grants(owner_id,user_id,can_review,can_approve,can_revoke,granted_by,granted_at,revoked_at)
 values(p_owner_id,p_user_id,coalesce(p_can_review,false),coalesce(p_can_approve,false),coalesce(p_can_revoke,false),auth.uid(),v_created,null)
 on conflict(owner_id,user_id) do update set can_review=excluded.can_review,can_approve=excluded.can_approve,
  can_revoke=excluded.can_revoke,granted_by=excluded.granted_by,granted_at=excluded.granted_at,revoked_at=null;
 select event_hash into v_prev from orchestrator.skill_lifecycle_actor_grant_events
  where owner_id=p_owner_id order by created_at desc,id desc limit 1;
 v_payload:=jsonb_build_object('id',v_id,'owner_id',p_owner_id,'subject_user_id',p_user_id,
  'event_type',v_type,'can_review',coalesce(p_can_review,false),'can_approve',coalesce(p_can_approve,false),
  'can_revoke',coalesce(p_can_revoke,false),'actor_user_id',auth.uid(),
  'previous_event_hash',v_prev,'created_at',v_created);
 v_event_hash:=pg_catalog.encode(extensions.digest(pg_catalog.convert_to(v_payload::text,'UTF8'),'sha256'),'hex');
 insert into orchestrator.skill_lifecycle_actor_grant_events(id,owner_id,subject_user_id,event_type,can_review,can_approve,can_revoke,actor_user_id,previous_event_hash,event_hash,created_at)
 values(v_id,p_owner_id,p_user_id,v_type,coalesce(p_can_review,false),coalesce(p_can_approve,false),coalesce(p_can_revoke,false),auth.uid(),v_prev,v_event_hash,v_created);
 return true;
end;
$function$;

create or replace function orchestrator.revoke_skill_lifecycle_actor_internal(p_owner_id uuid,p_user_id uuid)
returns boolean language plpgsql security definer set search_path=''
as $function$
declare v_old orchestrator.skill_lifecycle_actor_grants%rowtype;
 v_prev text; v_id uuid:=gen_random_uuid(); v_created timestamptz:=clock_timestamp();
 v_payload jsonb; v_event_hash text;
begin
 if auth.uid() is null or auth.uid()<>p_owner_id or p_user_id is null or p_user_id=p_owner_id then
  raise exception 'only the authenticated owner can revoke a non-owner lifecycle actor';
 end if;
 select * into v_old from orchestrator.skill_lifecycle_actor_grants
  where owner_id=p_owner_id and user_id=p_user_id;
 if not found then return false; end if;
 if v_old.revoked_at is not null then return true; end if;
 update orchestrator.skill_lifecycle_actor_grants set revoked_at=v_created
  where owner_id=p_owner_id and user_id=p_user_id;
 select event_hash into v_prev from orchestrator.skill_lifecycle_actor_grant_events
  where owner_id=p_owner_id order by created_at desc,id desc limit 1;
 v_payload:=jsonb_build_object('id',v_id,'owner_id',p_owner_id,'subject_user_id',p_user_id,
  'event_type','revoked','can_review',v_old.can_review,'can_approve',v_old.can_approve,
  'can_revoke',v_old.can_revoke,'actor_user_id',auth.uid(),
  'previous_event_hash',v_prev,'created_at',v_created);
 v_event_hash:=pg_catalog.encode(extensions.digest(pg_catalog.convert_to(v_payload::text,'UTF8'),'sha256'),'hex');
 insert into orchestrator.skill_lifecycle_actor_grant_events(id,owner_id,subject_user_id,event_type,can_review,can_approve,can_revoke,actor_user_id,previous_event_hash,event_hash,created_at)
 values(v_id,p_owner_id,p_user_id,'revoked',v_old.can_review,v_old.can_approve,v_old.can_revoke,auth.uid(),v_prev,v_event_hash,v_created);
 return true;
end;
$function$;

revoke all on function orchestrator.grant_skill_lifecycle_actor_internal(uuid,uuid,boolean,boolean,boolean) from public,anon,authenticated,service_role;
revoke all on function orchestrator.revoke_skill_lifecycle_actor_internal(uuid,uuid) from public,anon,authenticated,service_role;
grant execute on function orchestrator.grant_skill_lifecycle_actor_internal(uuid,uuid,boolean,boolean,boolean) to authenticated;
grant execute on function orchestrator.revoke_skill_lifecycle_actor_internal(uuid,uuid) to authenticated;

create or replace function public.grant_skill_lifecycle_actor(
 p_owner_id uuid,p_user_id uuid,p_can_review boolean default false,p_can_approve boolean default false,p_can_revoke boolean default false
) returns boolean language sql security invoker set search_path=''
as $function$ select orchestrator.grant_skill_lifecycle_actor_internal(p_owner_id,p_user_id,p_can_review,p_can_approve,p_can_revoke); $function$;
create or replace function public.revoke_skill_lifecycle_actor(p_owner_id uuid,p_user_id uuid)
returns boolean language sql security invoker set search_path=''
as $function$ select orchestrator.revoke_skill_lifecycle_actor_internal(p_owner_id,p_user_id); $function$;
revoke all on function public.grant_skill_lifecycle_actor(uuid,uuid,boolean,boolean,boolean) from public,anon,authenticated,service_role;
revoke all on function public.revoke_skill_lifecycle_actor(uuid,uuid) from public,anon,authenticated,service_role;
grant execute on function public.grant_skill_lifecycle_actor(uuid,uuid,boolean,boolean,boolean) to authenticated;
grant execute on function public.revoke_skill_lifecycle_actor(uuid,uuid) to authenticated;

-- Read-only audit verification helpers.
create or replace function orchestrator.verify_skill_lifecycle_control_chains_internal(p_owner_id uuid)
returns jsonb language plpgsql security definer set search_path=''
as $function$
declare r record; v_prev text; v_expected text; v_payload jsonb; v_count integer; v_bad uuid;
begin
 if not orchestrator.is_service_role_context() and (auth.uid() is null or (
  auth.uid()<>p_owner_id and not exists(select 1 from orchestrator.skill_lifecycle_actor_grants g
   where g.owner_id=p_owner_id and g.user_id=auth.uid() and g.revoked_at is null)))
 then raise exception 'authenticated owner or granted lifecycle actor required'; end if;

 v_prev:=null; v_count:=0;
 for r in select * from orchestrator.skill_lifecycle_policy_events where owner_id=p_owner_id order by created_at,id loop
  v_count:=v_count+1;
  if r.previous_event_hash is distinct from v_prev then return jsonb_build_object('policy_chain_valid',false,'policy_events_checked',v_count,'broken_event_id',r.id,'reason','previous_hash_mismatch'); end if;
  v_payload:=jsonb_build_object('id',r.id,'owner_id',r.owner_id,'policy_version',r.policy_version,'policy_hash',r.policy_hash,'event_type',r.event_type,'actor_user_id',r.actor_user_id,'reason',r.reason,'previous_event_hash',r.previous_event_hash,'created_at',r.created_at);
  v_expected:=pg_catalog.encode(extensions.digest(pg_catalog.convert_to(v_payload::text,'UTF8'),'sha256'),'hex');
  if r.event_hash<>v_expected then return jsonb_build_object('policy_chain_valid',false,'policy_events_checked',v_count,'broken_event_id',r.id,'reason','event_hash_mismatch'); end if;
  v_prev:=r.event_hash;
 end loop;

 v_prev:=null; v_count:=0;
 for r in select * from orchestrator.skill_lifecycle_actor_grant_events where owner_id=p_owner_id order by created_at,id loop
  v_count:=v_count+1;
  if r.previous_event_hash is distinct from v_prev then return jsonb_build_object('actor_grant_chain_valid',false,'actor_grant_events_checked',v_count,'broken_event_id',r.id,'reason','previous_hash_mismatch'); end if;
  v_payload:=jsonb_build_object('id',r.id,'owner_id',r.owner_id,'subject_user_id',r.subject_user_id,'event_type',r.event_type,'can_review',r.can_review,'can_approve',r.can_approve,'can_revoke',r.can_revoke,'actor_user_id',r.actor_user_id,'previous_event_hash',r.previous_event_hash,'created_at',r.created_at);
  v_expected:=pg_catalog.encode(extensions.digest(pg_catalog.convert_to(v_payload::text,'UTF8'),'sha256'),'hex');
  if r.event_hash<>v_expected then return jsonb_build_object('actor_grant_chain_valid',false,'actor_grant_events_checked',v_count,'broken_event_id',r.id,'reason','event_hash_mismatch'); end if;
  v_prev:=r.event_hash;
 end loop;
 return jsonb_build_object('policy_chain_valid',true,'policy_events_checked',
  (select count(*) from orchestrator.skill_lifecycle_policy_events where owner_id=p_owner_id),
  'actor_grant_chain_valid',true,'actor_grant_events_checked',
  (select count(*) from orchestrator.skill_lifecycle_actor_grant_events where owner_id=p_owner_id));
end;
$function$;
revoke all on function orchestrator.verify_skill_lifecycle_control_chains_internal(uuid) from public,anon,authenticated,service_role;
grant execute on function orchestrator.verify_skill_lifecycle_control_chains_internal(uuid) to authenticated,service_role;

create or replace function public.verify_skill_lifecycle_control_chains(p_owner_id uuid)
returns jsonb language sql security invoker set search_path=''
as $function$ select orchestrator.verify_skill_lifecycle_control_chains_internal(p_owner_id); $function$;
revoke all on function public.verify_skill_lifecycle_control_chains(uuid) from public,anon,authenticated,service_role;
grant execute on function public.verify_skill_lifecycle_control_chains(uuid) to authenticated,service_role;
