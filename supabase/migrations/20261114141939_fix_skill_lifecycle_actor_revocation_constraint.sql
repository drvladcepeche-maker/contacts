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
  set revoked_at=coalesce(revoked_at,now())
  where owner_id=p_owner_id and user_id=p_user_id;
 get diagnostics v_rows=row_count;
 return v_rows=1;
end;
$function$;
