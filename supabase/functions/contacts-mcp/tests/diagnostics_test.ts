import { assertEquals } from "jsr:@std/assert@1";
import { sanitizeDiagnostic } from "../diagnostics.ts";

Deno.test("redacts bearer credentials and credential assignments", () => {
  assertEquals(
    sanitizeDiagnostic("Authorization: Bearer abc.def.ghi api_key=example-secret"),
    "Authorization: Bearer [REDACTED] api_key=[REDACTED]",
  );
});

Deno.test("redacts contact, device, job and command identifiers", () => {
  assertEquals(
    sanitizeDiagnostic("contact_id=123 local ID 456 device-id:789 job id=abc command_id=def"),
    "contact_id=[REDACTED] local_id=[REDACTED] device_id=[REDACTED] job_id=[REDACTED] command_id=[REDACTED]",
  );
});

Deno.test("redacts emails, phone numbers, UUIDs and long tokens", () => {
  assertEquals(
    sanitizeDiagnostic("mail test@example.invalid phone +1 (555) 123-4567 id 123e4567-e89b-42d3-a456-426614174000"),
    "mail [EMAIL] phone [PHONE] id [ID]",
  );
  assertEquals(sanitizeDiagnostic("credential " + "a".repeat(64)), "credential [TOKEN]");
});

Deno.test("enforces output length", () => {
  assertEquals(sanitizeDiagnostic("abcdef", 3), "abc");
});
