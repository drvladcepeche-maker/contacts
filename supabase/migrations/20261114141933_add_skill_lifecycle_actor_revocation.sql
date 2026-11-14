create or replace function orchestrator.revoke_skill_lifecycle_actor_internal(p_owner_id uuid,p_user_id uuid)
returns boolean
language plpgsql
security definer
set search_path=''
as $function$
declare v_rows integer;
begin
 if auth.uid() is null or auth.uid()<>p_owner_id or p_user_id is null or p_user_id=p_owner_id then
  raise exception 'only the owner can revoke a non-owner lifecycle actor';
 end if;
 update orchestrator.skill_lifecycle_actor_grants
  set revoked_at=coalesce(revoked_at,now()),
      can_review=false,can_approve=false,can_revoke=false
  where owner_id=p_owner_id and user_id=p_user_id;
 get diagnostics v_rows=row_count;
 return v_rows=1;
end;
$function$;

revoke all on function orchestrator.revoke_skill_lifecycle_actor_internal(uuid,uuid) from public,anon,authenticated,service_role;
create or replace function public.revoke_skill_lifecycle_actor(p_owner_id uuid,p_user_id uuid)
returns boolean
language sql
security definer
set search_path=''
as $function$
 select orchestrator.revoke_skill_lifecycle_actor_internal(p_owner_id,p_user_id);
$function$;
revoke all on function public.revoke_skill_lifecycle_actor(uuid,uuid) from public,anon,authenticated,service_role;
grant execute on function public.revoke_skill_lifecycle_actor(uuid,uuid) to authenticated;
