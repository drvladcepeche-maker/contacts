-- The v3 worker bridge performs a server-side read to verify the active
-- owner-scoped queue lease before appending lifecycle evidence or posting a
-- Todoist comment. The service-role key remains inside the Edge Function.
-- Grant only SELECT; queue mutations continue through guarded RPCs.
GRANT SELECT ON TABLE orchestrator.orchestrator_tasks TO service_role;
