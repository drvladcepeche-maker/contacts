create table orchestrator.skill_lifecycle_events (
    id uuid primary key default gen_random_uuid(),
    owner_id uuid not null references auth.users(id) on delete restrict,
    skill_id text not null check (skill_id ~ '^[a-z][a-z0-9_.-]{1,127}$'),
    skill_version text not null check (skill_version ~ '^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-[0-9A-Za-z.-]+)?(\+[0-9A-Za-z.-]+)?$'),
    event_type text not null check (event_type in ('evaluation','review','approval','rejection','revocation')),
    actor_type text not null check (actor_type in ('worker','human')),
    actor_user_id uuid references auth.users(id) on delete restrict,
    actor_worker_id text,
    queue_task_id uuid references orchestrator.orchestrator_tasks(id) on delete restrict,
    policy_version text not null check (length(btrim(policy_version)) between 1 and 100),
    content_hash text not null check (content_hash ~ '^[a-f0-9]{64}$'),
    suite_id text,
    suite_version text,
    suite_hash text check (suite_hash is null or suite_hash ~ '^[a-f0-9]{64}$'),
    report_hash text check (report_hash is null or report_hash ~ '^[a-f0-9]{64}$'),
    evaluation_status text check (evaluation_status is null or evaluation_status in ('pass','fail','inconclusive')),
    related_event_id uuid references orchestrator.skill_lifecycle_events(id) on delete restrict,
    idempotency_key text not null check (length(btrim(idempotency_key)) between 1 and 256),
    event_payload jsonb not null default '{}'::jsonb check (
      jsonb_typeof(event_payload) = 'object' and octet_length(event_payload::text) <= 16384
    ),
    previous_event_hash text check (previous_event_hash is null or previous_event_hash ~ '^[a-f0-9]{64}$'),
    event_hash text not null unique check (event_hash ~ '^[a-f0-9]{64}$'),
    created_at timestamptz not null default clock_timestamp(),
    constraint skill_lifecycle_actor_shape check (
      (actor_type = 'worker' and actor_user_id is null and actor_worker_id is not null
       and queue_task_id is not null and event_type = 'evaluation')
      or
      (actor_type = 'human' and actor_user_id is not null and actor_worker_id is null
       and queue_task_id is null and event_type in ('review','approval','rejection','revocation'))
    ),
    constraint skill_lifecycle_evaluation_shape check (
      (event_type = 'evaluation' and suite_id is not null and suite_version is not null
       and suite_hash is not null and report_hash is not null and evaluation_status is not null
       and related_event_id is null)
      or
      (event_type = 'review' and suite_hash is not null and report_hash is not null
       and evaluation_status is null and related_event_id is null)
      or
      (event_type in ('approval','rejection','revocation') and related_event_id is not null
       and evaluation_status is null)
    ),
    constraint skill_lifecycle_idempotency_unique unique (owner_id, idempotency_key)
);

comment on table orchestrator.skill_lifecycle_events is
'Append-only, hash-chained evidence ledger for Skill Evaluation, Review, Approval, Rejection and Revocation. No direct table access; use guarded RPC functions.';

create index skill_lifecycle_events_revision_idx
  on orchestrator.skill_lifecycle_events(owner_id, skill_id, skill_version, created_at desc, id desc);

alter table orchestrator.skill_lifecycle_events enable row level security;
alter table orchestrator.skill_lifecycle_events force row level security;

revoke all on table orchestrator.skill_lifecycle_events from public, anon, authenticated, service_role;

create or replace function orchestrator.reject_skill_lifecycle_mutation()
returns trigger
language plpgsql
security definer
set search_path = ''
as $function$
begin
  raise exception 'skill lifecycle ledger is append-only';
end;
$function$;

revoke all on function orchestrator.reject_skill_lifecycle_mutation() from public, anon, authenticated, service_role;

create trigger skill_lifecycle_no_update_delete
before update or delete on orchestrator.skill_lifecycle_events
for each row execute function orchestrator.reject_skill_lifecycle_mutation();

create trigger skill_lifecycle_no_truncate
before truncate on orchestrator.skill_lifecycle_events
for each statement execute function orchestrator.reject_skill_lifecycle_mutation();

create or replace function public.append_skill_lifecycle_event(
    p_owner_id uuid,
    p_skill_id text,
    p_skill_version text,
    p_event_type text,
    p_policy_version text,
    p_content_hash text,
    p_idempotency_key text,
    p_suite_id text default null,
    p_suite_version text default null,
    p_suite_hash text default null,
    p_report_hash text default null,
    p_evaluation_status text default null,
    p_related_event_id uuid default null,
    p_event_payload jsonb default '{}'::jsonb,
    p_queue_task_id uuid default null,
    p_lease_token text default null,
    p_actor_worker_id text default null
)
returns uuid
language plpgsql
security definer
set search_path = ''
as $function$
declare
    v_actor_type text;
    v_actor_user_id uuid;
    v_previous_hash text;
    v_event_hash text;
    v_event_id uuid := gen_random_uuid();
    v_created_at timestamptz := clock_timestamp();
    v_payload jsonb;
    v_existing orchestrator.skill_lifecycle_events%rowtype;
    v_related orchestrator.skill_lifecycle_events%rowtype;
    v_evaluation orchestrator.skill_lifecycle_events%rowtype;
begin
    if p_owner_id is null
       or p_skill_id is null or p_skill_id !~ '^[a-z][a-z0-9_.-]{1,127}$'
       or p_skill_version is null or p_skill_version !~ '^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9]*)(-[0-9A-Za-z.-]+)?(\+[0-9A-Za-z.-]+)?$'
       or p_event_type not in ('evaluation','review','approval','rejection','revocation')
       or p_policy_version is null or length(btrim(p_policy_version)) not between 1 and 100
       or p_content_hash is null or p_content_hash !~ '^[a-f0-9]{64}$'
       or p_idempotency_key is null or length(btrim(p_idempotency_key)) not between 1 and 256
       or p_event_payload is null or jsonb_typeof(p_event_payload) <> 'object'
       or octet_length(p_event_payload::text) > 16384 then
        raise exception 'invalid skill lifecycle event';
    end if;

    if orchestrator.is_service_role_context() then
        if p_event_type <> 'evaluation' or p_queue_task_id is null
           or p_lease_token is null or p_actor_worker_id is null
           or p_actor_worker_id !~ '^[a-z0-9][a-z0-9._-]{0,99}$'
           or p_suite_id is null or p_suite_version is null
           or p_suite_hash is null or p_suite_hash !~ '^[a-f0-9]{64}$'
           or p_report_hash is null or p_report_hash !~ '^[a-f0-9]{64}$'
           or p_evaluation_status not in ('pass','fail','inconclusive')
           or p_related_event_id is not null then
            raise exception 'worker may append evaluation events only';
        end if;
        if not exists (
            select 1
              from orchestrator.orchestrator_tasks t
             where t.id = p_queue_task_id
               and t.task_type = 'skill.evaluate'
               and t.status = 'running'
               and t.worker_id = p_actor_worker_id
               and t.lease_token::text = p_lease_token
               and t.lease_expires_at > clock_timestamp()
               and t.payload->>'owner_id' = p_owner_id::text
        ) then
            raise exception 'stale, unowned, or incorrectly scoped skill evaluation lease';
        end if;
        v_actor_type := 'worker';
        v_actor_user_id := null;
    else
        v_actor_user_id := auth.uid();
        if v_actor_user_id is null or v_actor_user_id <> p_owner_id
           or p_event_type not in ('review','approval','rejection','revocation')
           or p_queue_task_id is not null or p_lease_token is not null
           or p_actor_worker_id is not null then
            raise exception 'authenticated owner identity required for human lifecycle events';
        end if;
        v_actor_type := 'human';
        if p_event_type in ('review','approval','rejection','revocation') then
            if p_suite_hash is null or p_suite_hash !~ '^[a-f0-9]{64}$'
               or p_report_hash is null or p_report_hash !~ '^[a-f0-9]{64}$' then
                raise exception 'human lifecycle events must bind exact suite and report hashes';
            end if;
        end if;
    end if;

    perform pg_catalog.pg_advisory_xact_lock(
      pg_catalog.hashtextextended(p_owner_id::text || ':' || p_skill_id || ':' || p_skill_version, 0)
    );

    select * into v_existing
      from orchestrator.skill_lifecycle_events
     where owner_id = p_owner_id and idempotency_key = p_idempotency_key;
    if found then
        if v_existing.skill_id = p_skill_id
           and v_existing.skill_version = p_skill_version
           and v_existing.event_type = p_event_type
           and v_existing.content_hash = p_content_hash
           and v_existing.report_hash is not distinct from p_report_hash
           and v_existing.suite_hash is not distinct from p_suite_hash
           and v_existing.related_event_id is not distinct from p_related_event_id then
            return v_existing.id;
        end if;
        raise exception 'idempotency key reused with different lifecycle event';
    end if;

    if p_event_type = 'review' then
        select * into v_evaluation
          from orchestrator.skill_lifecycle_events
         where owner_id = p_owner_id and skill_id = p_skill_id
           and skill_version = p_skill_version and event_type = 'evaluation'
           and content_hash = p_content_hash and suite_hash = p_suite_hash
           and report_hash = p_report_hash and evaluation_status = 'pass'
         order by created_at desc, id desc limit 1;
        if not found then
            raise exception 'review requires a passing evaluation for the exact content, suite and report';
        end if;
    elsif p_event_type = 'approval' then
        select * into v_related
          from orchestrator.skill_lifecycle_events
         where id = p_related_event_id and owner_id = p_owner_id
           and skill_id = p_skill_id and skill_version = p_skill_version
           and event_type = 'review' and content_hash = p_content_hash
           and suite_hash = p_suite_hash and report_hash = p_report_hash
           and actor_user_id <> v_actor_user_id;
        if not found then
            raise exception 'approval requires a matching human review by a different user';
        end if;
        if exists (
            select 1 from orchestrator.skill_lifecycle_events
             where owner_id = p_owner_id and skill_id = p_skill_id
               and skill_version = p_skill_version and event_type = 'approval'
               and content_hash = p_content_hash
        ) then
            raise exception 'this exact skill revision already has an approval event';
        end if;
    elsif p_event_type = 'rejection' then
        if not exists (
            select 1 from orchestrator.skill_lifecycle_events
             where id = p_related_event_id and owner_id = p_owner_id
               and skill_id = p_skill_id and skill_version = p_skill_version
               and event_type = 'review' and content_hash = p_content_hash
        ) then
            raise exception 'rejection requires a matching review event';
        end if;
    elsif p_event_type = 'revocation' then
        select * into v_related
          from orchestrator.skill_lifecycle_events
         where id = p_related_event_id and owner_id = p_owner_id
           and skill_id = p_skill_id and skill_version = p_skill_version
           and event_type = 'approval' and content_hash = p_content_hash
           and suite_hash = p_suite_hash and report_hash = p_report_hash;
        if not found then
            raise exception 'revocation requires a matching approval event';
        end if;
        if exists (
            select 1 from orchestrator.skill_lifecycle_events
             where owner_id = p_owner_id and related_event_id = p_related_event_id
               and event_type = 'revocation'
        ) then
            raise exception 'approval has already been revoked';
        end if;
    end if;

    select event_hash into v_previous_hash
      from orchestrator.skill_lifecycle_events
     where owner_id = p_owner_id and skill_id = p_skill_id and skill_version = p_skill_version
     order by created_at desc, id desc limit 1;

    v_payload := jsonb_build_object(
      'id', v_event_id, 'owner_id', p_owner_id, 'skill_id', p_skill_id,
      'skill_version', p_skill_version, 'event_type', p_event_type,
      'actor_type', v_actor_type, 'actor_user_id', v_actor_user_id,
      'actor_worker_id', case when v_actor_type = 'worker' then p_actor_worker_id else null end,
      'queue_task_id', case when v_actor_type = 'worker' then p_queue_task_id else null end,
      'policy_version', p_policy_version, 'content_hash', p_content_hash,
      'suite_id', p_suite_id, 'suite_version', p_suite_version,
      'suite_hash', p_suite_hash, 'report_hash', p_report_hash,
      'evaluation_status', p_evaluation_status, 'related_event_id', p_related_event_id,
      'idempotency_key', p_idempotency_key, 'event_payload', p_event_payload,
      'previous_event_hash', v_previous_hash, 'created_at', v_created_at
    );
    v_event_hash := pg_catalog.encode(
      extensions.digest(pg_catalog.convert_to(v_payload::text, 'UTF8'), 'sha256'),
      'hex'
    );

    insert into orchestrator.skill_lifecycle_events (
      id, owner_id, skill_id, skill_version, event_type, actor_type,
      actor_user_id, actor_worker_id, queue_task_id, policy_version, content_hash,
      suite_id, suite_version, suite_hash, report_hash, evaluation_status,
      related_event_id, idempotency_key, event_payload, previous_event_hash,
      event_hash, created_at
    ) values (
      v_event_id, p_owner_id, p_skill_id, p_skill_version, p_event_type, v_actor_type,
      v_actor_user_id, case when v_actor_type = 'worker' then p_actor_worker_id else null end,
      case when v_actor_type = 'worker' then p_queue_task_id else null end,
      p_policy_version, p_content_hash, p_suite_id, p_suite_version, p_suite_hash,
      p_report_hash, p_evaluation_status, p_related_event_id, p_idempotency_key,
      p_event_payload, v_previous_hash, v_event_hash, v_created_at
    );

    return v_event_id;
end;
$function$;

revoke all on function public.append_skill_lifecycle_event(
  uuid,text,text,text,text,text,text,text,text,text,text,text,uuid,jsonb,uuid,text,text
) from public, anon, authenticated, service_role;
grant execute on function public.append_skill_lifecycle_event(
  uuid,text,text,text,text,text,text,text,text,text,text,text,uuid,jsonb,uuid,text,text
) to authenticated, service_role;

create or replace function public.get_skill_lifecycle_events(
    p_owner_id uuid,
    p_skill_id text,
    p_skill_version text default null
)
returns setof orchestrator.skill_lifecycle_events
language plpgsql
security definer
set search_path = ''
as $function$
declare v_event orchestrator.skill_lifecycle_events%rowtype;
begin
    if not orchestrator.is_service_role_context()
       and (auth.uid() is null or auth.uid() <> p_owner_id) then
        raise exception 'authenticated owner identity required';
    end if;
    for v_event in
      select * from orchestrator.skill_lifecycle_events
       where owner_id = p_owner_id and skill_id = p_skill_id
         and (p_skill_version is null or skill_version = p_skill_version)
       order by created_at, id
    loop
      return next v_event;
    end loop;
    return;
end;
$function$;

revoke all on function public.get_skill_lifecycle_events(uuid,text,text) from public, anon, authenticated, service_role;
grant execute on function public.get_skill_lifecycle_events(uuid,text,text) to authenticated, service_role;
