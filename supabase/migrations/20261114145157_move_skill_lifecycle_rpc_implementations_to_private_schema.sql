
create schema if not exists skill_private;
revoke all on schema skill_private from public,anon;
grant usage on schema skill_private to authenticated,service_role;

alter function orchestrator.append_skill_lifecycle_event_internal(uuid,text,text,text,text,text,text,text,text,text,text,text,uuid,jsonb,uuid,text,text) set schema skill_private;
alter function orchestrator.append_skill_lifecycle_event_unbound_internal(uuid,text,text,text,text,text,text,text,text,text,text,text,uuid,jsonb,uuid,text,text) set schema skill_private;
alter function orchestrator.get_skill_lifecycle_events_internal(uuid,text,text) set schema skill_private;
alter function orchestrator.grant_skill_lifecycle_actor_internal(uuid,uuid,boolean,boolean,boolean) set schema skill_private;
alter function orchestrator.issue_skill_evaluation_worker_credential_internal(uuid) set schema skill_private;
alter function orchestrator.register_skill_lifecycle_policy_internal(uuid,text,jsonb) set schema skill_private;
alter function orchestrator.revoke_skill_evaluation_worker_credential_internal(uuid) set schema skill_private;
alter function orchestrator.revoke_skill_lifecycle_actor_internal(uuid,uuid) set schema skill_private;
alter function orchestrator.revoke_skill_lifecycle_policy_internal(uuid,text,text) set schema skill_private;
alter function orchestrator.verify_skill_lifecycle_chain_internal(uuid,text,text) set schema skill_private;
alter function orchestrator.verify_skill_lifecycle_control_chains_internal(uuid) set schema skill_private;
alter function public.register_todoist_control_target(uuid,text,text) set schema skill_private;

do $rewrite$
declare v_def text;
begin
 v_def:=pg_catalog.pg_get_functiondef('skill_private.append_skill_lifecycle_event_internal(uuid,text,text,text,text,text,text,text,text,text,text,text,uuid,jsonb,uuid,text,text)'::regprocedure);
 v_def:=replace(v_def,'orchestrator.append_skill_lifecycle_event_unbound_internal','skill_private.append_skill_lifecycle_event_unbound_internal');
 execute v_def;
end;
$rewrite$;

revoke all on all functions in schema skill_private from public,anon;
grant execute on function skill_private.append_skill_lifecycle_event_internal(uuid,text,text,text,text,text,text,text,text,text,text,text,uuid,jsonb,uuid,text,text) to authenticated,service_role;
grant execute on function skill_private.append_skill_lifecycle_event_unbound_internal(uuid,text,text,text,text,text,text,text,text,text,text,text,uuid,jsonb,uuid,text,text) to authenticated,service_role;
grant execute on function skill_private.get_skill_lifecycle_events_internal(uuid,text,text) to authenticated,service_role;
grant execute on function skill_private.grant_skill_lifecycle_actor_internal(uuid,uuid,boolean,boolean,boolean) to authenticated,service_role;
grant execute on function skill_private.issue_skill_evaluation_worker_credential_internal(uuid) to authenticated;
grant execute on function skill_private.register_skill_lifecycle_policy_internal(uuid,text,jsonb) to authenticated;
grant execute on function skill_private.revoke_skill_evaluation_worker_credential_internal(uuid) to authenticated;
grant execute on function skill_private.revoke_skill_lifecycle_actor_internal(uuid,uuid) to authenticated;
grant execute on function skill_private.revoke_skill_lifecycle_policy_internal(uuid,text,text) to authenticated;
grant execute on function skill_private.verify_skill_lifecycle_chain_internal(uuid,text,text) to authenticated,service_role;
grant execute on function skill_private.verify_skill_lifecycle_control_chains_internal(uuid) to authenticated,service_role;
grant execute on function skill_private.register_todoist_control_target(uuid,text,text) to authenticated;

create or replace function public.append_skill_lifecycle_event(
 p_owner_id uuid,p_skill_id text,p_skill_version text,p_event_type text,p_policy_version text,
 p_content_hash text,p_idempotency_key text,p_suite_id text default null,p_suite_version text default null,
 p_suite_hash text default null,p_report_hash text default null,p_evaluation_status text default null,
 p_related_event_id uuid default null,p_event_payload jsonb default '{}'::jsonb,p_queue_task_id uuid default null,
 p_lease_token text default null,p_actor_worker_id text default null
) returns uuid language sql security invoker set search_path=''
as $function$ select skill_private.append_skill_lifecycle_event_internal(
 p_owner_id,p_skill_id,p_skill_version,p_event_type,p_policy_version,p_content_hash,p_idempotency_key,
 p_suite_id,p_suite_version,p_suite_hash,p_report_hash,p_evaluation_status,p_related_event_id,
 p_event_payload,p_queue_task_id,p_lease_token,p_actor_worker_id); $function$;

create or replace function public.get_skill_lifecycle_events(p_owner_id uuid,p_skill_id text,p_skill_version text default null)
returns jsonb language sql security invoker set search_path=''
as $function$ select skill_private.get_skill_lifecycle_events_internal(p_owner_id,p_skill_id,p_skill_version); $function$;

create or replace function public.grant_skill_lifecycle_actor(
 p_owner_id uuid,p_user_id uuid,p_can_review boolean default false,p_can_approve boolean default false,p_can_revoke boolean default false
) returns boolean language sql security invoker set search_path=''
as $function$ select skill_private.grant_skill_lifecycle_actor_internal(p_owner_id,p_user_id,p_can_review,p_can_approve,p_can_revoke); $function$;

create or replace function public.revoke_skill_lifecycle_actor(p_owner_id uuid,p_user_id uuid)
returns boolean language sql security invoker set search_path=''
as $function$ select skill_private.revoke_skill_lifecycle_actor_internal(p_owner_id,p_user_id); $function$;

create or replace function public.register_skill_lifecycle_policy(p_owner_id uuid,p_policy_version text,p_policy_document jsonb)
returns text language sql security invoker set search_path=''
as $function$ select skill_private.register_skill_lifecycle_policy_internal(p_owner_id,p_policy_version,p_policy_document); $function$;

create or replace function public.revoke_skill_lifecycle_policy(p_owner_id uuid,p_policy_version text,p_reason text)
returns boolean language sql security invoker set search_path=''
as $function$ select skill_private.revoke_skill_lifecycle_policy_internal(p_owner_id,p_policy_version,p_reason); $function$;

create or replace function public.issue_skill_evaluation_worker_credential(p_owner_id uuid)
returns text language sql security invoker set search_path=''
as $function$ select skill_private.issue_skill_evaluation_worker_credential_internal(p_owner_id); $function$;

create or replace function public.revoke_skill_evaluation_worker_credential(p_owner_id uuid)
returns integer language sql security invoker set search_path=''
as $function$ select skill_private.revoke_skill_evaluation_worker_credential_internal(p_owner_id); $function$;

create or replace function public.verify_skill_lifecycle_chain(p_owner_id uuid,p_skill_id text,p_skill_version text)
returns jsonb language sql security invoker set search_path=''
as $function$ select skill_private.verify_skill_lifecycle_chain_internal(p_owner_id,p_skill_id,p_skill_version); $function$;

create or replace function public.verify_skill_lifecycle_control_chains(p_owner_id uuid)
returns jsonb language sql security invoker set search_path=''
as $function$ select skill_private.verify_skill_lifecycle_control_chains_internal(p_owner_id); $function$;

create or replace function public.register_todoist_control_target(p_owner_id uuid,p_todoist_task_id text,p_purpose text)
returns boolean language sql security invoker set search_path=''
as $function$ select skill_private.register_todoist_control_target(p_owner_id,p_todoist_task_id,p_purpose); $function$;

revoke all on function public.append_skill_lifecycle_event(uuid,text,text,text,text,text,text,text,text,text,text,text,uuid,jsonb,uuid,text,text) from public,anon,authenticated,service_role;
revoke all on function public.get_skill_lifecycle_events(uuid,text,text) from public,anon,authenticated,service_role;
revoke all on function public.grant_skill_lifecycle_actor(uuid,uuid,boolean,boolean,boolean) from public,anon,authenticated,service_role;
revoke all on function public.revoke_skill_lifecycle_actor(uuid,uuid) from public,anon,authenticated,service_role;
revoke all on function public.register_skill_lifecycle_policy(uuid,text,jsonb) from public,anon,authenticated,service_role;
revoke all on function public.revoke_skill_lifecycle_policy(uuid,text,text) from public,anon,authenticated,service_role;
revoke all on function public.issue_skill_evaluation_worker_credential(uuid) from public,anon,authenticated,service_role;
revoke all on function public.revoke_skill_evaluation_worker_credential(uuid) from public,anon,authenticated,service_role;
revoke all on function public.verify_skill_lifecycle_chain(uuid,text,text) from public,anon,authenticated,service_role;
revoke all on function public.verify_skill_lifecycle_control_chains(uuid) from public,anon,authenticated,service_role;
revoke all on function public.register_todoist_control_target(uuid,text,text) from public,anon,authenticated,service_role;

grant execute on function public.append_skill_lifecycle_event(uuid,text,text,text,text,text,text,text,text,text,text,text,uuid,jsonb,uuid,text,text) to authenticated,service_role;
grant execute on function public.get_skill_lifecycle_events(uuid,text,text) to authenticated,service_role;
grant execute on function public.grant_skill_lifecycle_actor(uuid,uuid,boolean,boolean,boolean) to authenticated;
grant execute on function public.revoke_skill_lifecycle_actor(uuid,uuid) to authenticated;
grant execute on function public.register_skill_lifecycle_policy(uuid,text,jsonb) to authenticated;
grant execute on function public.revoke_skill_lifecycle_policy(uuid,text,text) to authenticated;
grant execute on function public.issue_skill_evaluation_worker_credential(uuid) to authenticated;
grant execute on function public.revoke_skill_evaluation_worker_credential(uuid) to authenticated;
grant execute on function public.verify_skill_lifecycle_chain(uuid,text,text) to authenticated,service_role;
grant execute on function public.verify_skill_lifecycle_control_chains(uuid) to authenticated,service_role;
grant execute on function public.register_todoist_control_target(uuid,text,text) to authenticated;
