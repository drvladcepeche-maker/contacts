export function sanitizeDiagnostic(value: unknown, maxLength = 2000): string {
  let safe = String(value ?? "");
  safe = safe.replace(/Bearer\\s+[A-Za-z0-9._~+/-]+=*/gi, "Bearer [REDACTED]");
  safe = safe.replace(
    /\\b(token|password|secret|api[_-]?key|authorization|access[_-]?token)\\s*[:=]\\s*[^\\s,;]+/gi,
    "$1=[REDACTED]",
  );
  safe = safe.replace(
    /\\b(command|job|device|contact|local)[_-]?id\\b(\\s*[:=]\\s*|\\s+)[^\\s,;]+/gi,
    "$1_id=[REDACTED]",
  );
  safe = safe.replace(/\\b[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\\b/gi, "[ID]");
  safe = safe.replace(/[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}/gi, "[EMAIL]");
  safe = safe.replace(/\\+?\\d[\\d ()-]{7,}\\d/g, "[PHONE]");
  safe = safe.replace(/\\b[A-Fa-f0-9]{32,}\\b/g, "[TOKEN]");
  return safe.slice(0, Math.max(0, maxLength));
}
