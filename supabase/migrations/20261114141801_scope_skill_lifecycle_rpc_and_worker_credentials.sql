alter table orchestrator.worker_credentials drop constraint worker_credentials_scopes_check;
alter table orchestrator.worker_credentials add constraint worker_credentials_scopes_check
 check (
   cardinality(scopes) between 1 and 5
   and scopes <@ array['queue.claim','queue.complete','queue.renew','queue.fail','skill.evaluation.append']::text[]
 );

revoke all on function orchestrator.append_skill_lifecycle_event_internal(uuid,text,text,text,text,text,text,text,text,text,text,text,uuid,jsonb,uuid,text,text)
 from public,anon,authenticated,service_role;
revoke all on function orchestrator.get_skill_lifecycle_events_internal(uuid,text,text)
 from public,anon,authenticated,service_role;
revoke all on function orchestrator.grant_skill_lifecycle_actor_internal(uuid,uuid,boolean,boolean,boolean)
 from public,anon,authenticated,service_role;
revoke all on function orchestrator.verify_skill_lifecycle_chain_internal(uuid,text,text)
 from public,anon,authenticated,service_role;

grant usage on schema orchestrator to authenticated,service_role;

alter function public.append_skill_lifecycle_event(uuid,text,text,text,text,text,text,text,text,text,text,text,uuid,jsonb,uuid,text,text)
 security definer;
alter function public.get_skill_lifecycle_events(uuid,text,text) security definer;
alter function public.grant_skill_lifecycle_actor(uuid,uuid,boolean,boolean,boolean) security definer;
alter function public.verify_skill_lifecycle_chain(uuid,text,text) security definer;

revoke all on function public.append_skill_lifecycle_event(uuid,text,text,text,text,text,text,text,text,text,text,text,uuid,jsonb,uuid,text,text)
 from public,anon,authenticated,service_role;
revoke all on function public.get_skill_lifecycle_events(uuid,text,text) from public,anon,authenticated,service_role;
revoke all on function public.grant_skill_lifecycle_actor(uuid,uuid,boolean,boolean,boolean) from public,anon,authenticated,service_role;
revoke all on function public.verify_skill_lifecycle_chain(uuid,text,text) from public,anon,authenticated,service_role;

grant execute on function public.append_skill_lifecycle_event(uuid,text,text,text,text,text,text,text,text,text,text,text,uuid,jsonb,uuid,text,text)
 to authenticated,service_role;
grant execute on function public.get_skill_lifecycle_events(uuid,text,text) to authenticated,service_role;
grant execute on function public.grant_skill_lifecycle_actor(uuid,uuid,boolean,boolean,boolean) to authenticated;
grant execute on function public.verify_skill_lifecycle_chain(uuid,text,text) to authenticated,service_role;
