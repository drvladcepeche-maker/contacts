ALTER TABLE public.termux_bridge_jobs
  ADD COLUMN IF NOT EXISTS claim_token uuid;

CREATE UNIQUE INDEX IF NOT EXISTS termux_bridge_jobs_device_claim_token_uidx
  ON public.termux_bridge_jobs (device_id, claim_token)
  WHERE claim_token IS NOT NULL;

DROP FUNCTION IF EXISTS public.claim_termux_bridge_job(uuid);

CREATE OR REPLACE FUNCTION public.claim_termux_bridge_job(
  p_device_id uuid,
  p_claim_token uuid
)
RETURNS SETOF public.termux_bridge_jobs
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public', 'pg_temp'
AS $function$
BEGIN
  IF p_device_id IS NULL OR p_claim_token IS NULL THEN
    RAISE EXCEPTION 'device_id and claim_token are required';
  END IF;

  -- Serialize retries carrying the same idempotency key for this device.
  PERFORM pg_advisory_xact_lock(
    hashtextextended(p_device_id::text || ':' || p_claim_token::text, 0)
  );

  -- If the original claim committed but its response was lost, return that
  -- same running job instead of claiming a second job.
  RETURN QUERY
    SELECT job.*
    FROM public.termux_bridge_jobs AS job
    WHERE job.device_id = p_device_id
      AND job.claim_token = p_claim_token
      AND job.status = 'running'
    LIMIT 1;

  IF FOUND THEN
    RETURN;
  END IF;

  -- A completed/failed job may still own this token if the client lost its
  -- local state. Do not reuse that token for a different job.
  IF EXISTS (
    SELECT 1
    FROM public.termux_bridge_jobs AS job
    WHERE job.device_id = p_device_id
      AND job.claim_token = p_claim_token
  ) THEN
    RETURN;
  END IF;

  RETURN QUERY
    WITH next_job AS (
      SELECT job.id
      FROM public.termux_bridge_jobs AS job
      WHERE job.device_id = p_device_id
        AND job.status = 'queued'
      ORDER BY job.created_at ASC
      LIMIT 1
      FOR UPDATE SKIP LOCKED
    )
    UPDATE public.termux_bridge_jobs AS job
    SET status = 'running',
        started_at = COALESCE(job.started_at, now()),
        claim_token = p_claim_token
    FROM next_job
    WHERE job.id = next_job.id
    RETURNING job.*;
END;
$function$;

REVOKE ALL ON FUNCTION public.claim_termux_bridge_job(uuid, uuid) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.claim_termux_bridge_job(uuid, uuid) FROM anon, authenticated;
GRANT EXECUTE ON FUNCTION public.claim_termux_bridge_job(uuid, uuid) TO service_role;
