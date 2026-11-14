
-- Internal SECURITY DEFINER routines are not direct RPC surfaces.
revoke all on function orchestrator.append_skill_lifecycle_event_internal(uuid,text,text,text,text,text,text,text,text,text,text,text,uuid,jsonb,uuid,text,text) from public,anon,authenticated,service_role;
revoke all on function orchestrator.append_skill_lifecycle_event_unbound_internal(uuid,text,text,text,text,text,text,text,text,text,text,text,uuid,jsonb,uuid,text,text) from public,anon,authenticated,service_role;
revoke all on function orchestrator.register_skill_lifecycle_policy_internal(uuid,text,jsonb) from public,anon,authenticated,service_role;
revoke all on function orchestrator.revoke_skill_lifecycle_policy_internal(uuid,text,text) from public,anon,authenticated,service_role;
revoke all on function orchestrator.grant_skill_lifecycle_actor_internal(uuid,uuid,boolean,boolean,boolean) from public,anon,authenticated,service_role;
revoke all on function orchestrator.revoke_skill_lifecycle_actor_internal(uuid,uuid) from public,anon,authenticated,service_role;
revoke all on function orchestrator.verify_skill_lifecycle_control_chains_internal(uuid) from public,anon,authenticated,service_role;
revoke usage on schema orchestrator from authenticated;

-- Public RPC wrappers are the only authenticated entry points. They retain a fixed empty search_path
-- and delegate to internal routines that enforce auth.uid(), owner scope, grants and queue leases.
alter function public.append_skill_lifecycle_event(uuid,text,text,text,text,text,text,text,text,text,text,text,uuid,jsonb,uuid,text,text) security definer;
alter function public.register_skill_lifecycle_policy(uuid,text,jsonb) security definer;
alter function public.revoke_skill_lifecycle_policy(uuid,text,text) security definer;
alter function public.grant_skill_lifecycle_actor(uuid,uuid,boolean,boolean,boolean) security definer;
alter function public.revoke_skill_lifecycle_actor(uuid,uuid) security definer;
alter function public.verify_skill_lifecycle_control_chains(uuid) security definer;

create index if not exists skill_lifecycle_actor_grant_events_actor_idx
 on orchestrator.skill_lifecycle_actor_grant_events(actor_user_id);
create index if not exists skill_lifecycle_actor_grant_events_subject_idx
 on orchestrator.skill_lifecycle_actor_grant_events(subject_user_id);
create index if not exists skill_lifecycle_policy_events_actor_idx
 on orchestrator.skill_lifecycle_policy_events(actor_user_id);
create index if not exists skill_lifecycle_policy_registry_registered_by_idx
 on orchestrator.skill_lifecycle_policy_registry(registered_by);
create index if not exists skill_lifecycle_actor_grants_user_idx
 on orchestrator.skill_lifecycle_actor_grants(user_id);
create index if not exists skill_lifecycle_actor_grants_granted_by_idx
 on orchestrator.skill_lifecycle_actor_grants(granted_by);
create index if not exists skill_lifecycle_events_actor_user_idx
 on orchestrator.skill_lifecycle_events(actor_user_id);
create index if not exists skill_lifecycle_events_queue_task_idx
 on orchestrator.skill_lifecycle_events(queue_task_id);
create index if not exists skill_lifecycle_events_related_event_idx
 on orchestrator.skill_lifecycle_events(related_event_id);
