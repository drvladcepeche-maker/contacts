
create or replace function orchestrator.issue_skill_evaluation_worker_credential_internal(p_owner_id uuid)
returns text language plpgsql security definer set search_path=''
as $function$
declare v_token text; v_hash text; v_prefix text; v_now timestamptz:=clock_timestamp();
begin
 if auth.uid() is null or auth.uid()<>p_owner_id then
  raise exception 'authenticated project owner identity required';
 end if;
 update orchestrator.worker_credentials set revoked_at=v_now
 where owner_id=p_owner_id and worker_id='osiris-skill-evaluator' and revoked_at is null;
 v_token:='orch_'||rtrim(translate(encode(extensions.gen_random_bytes(48),'base64'),'+/','-_'),'=');
 v_hash:=pg_catalog.encode(extensions.digest(pg_catalog.convert_to(v_token,'UTF8'),'sha256'),'hex');
 v_prefix:=left(v_token,16);
 insert into orchestrator.worker_credentials(owner_id,worker_id,token_hash,token_prefix,scopes,allowed_task_types,created_at,expires_at)
 values(p_owner_id,'osiris-skill-evaluator',v_hash,v_prefix,
  array['queue.claim','queue.complete','queue.renew','queue.fail','skill.evaluation.append']::text[],
  array['skill.evaluate']::text[],v_now,v_now+interval '48 hours');
 return v_token;
end;
$function$;
