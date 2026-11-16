export function calculateBatchResumeAt(
  lastRequestAt: string | null | undefined,
  nowMs = Date.now(),
  cooldownMs = 60 * 60_000,
): string {
  const lastRequestMs = lastRequestAt ? Date.parse(lastRequestAt) : Number.NaN;
  const resumeMs = Number.isFinite(lastRequestMs)
    ? lastRequestMs + cooldownMs
    : nowMs + 60_000;
  return new Date(resumeMs).toISOString();
}

export function adaptiveRequestIntervalMs(baseIntervalMs: number, floodWaitCount = 0, maxIntervalMs = 5 * 60_000): number {
  const safeBase = Math.max(5_000, Number.isFinite(baseIntervalMs) ? baseIntervalMs : 5_000);
  const safeCount = Number.isFinite(floodWaitCount) ? Math.max(0, Math.floor(floodWaitCount)) : 0;
  const safeMaximum = Math.max(safeBase, Number.isFinite(maxIntervalMs) ? maxIntervalMs : 5 * 60_000);
  return Math.min(safeMaximum, safeBase + safeCount * 60_000);
}

export function calculateFloodWaitResumeAt(waitSeconds: number, nowMs = Date.now()): string {
  const floored = Math.floor(waitSeconds);
  const safeWaitSeconds = Number.isSafeInteger(floored) && floored > 0 ? floored : 1;
  return new Date(nowMs + (safeWaitSeconds + 60) * 1000).toISOString();
}

export function floodWaitSeconds(error: unknown): number | null {
  const pending: unknown[] = [error];
  const seen = new Set<unknown>();
  while (pending.length > 0) {
    const current = pending.shift();
    if (current == null || seen.has(current)) continue;
    if (typeof current === "object" || typeof current === "function") seen.add(current);
    if (typeof current === "object" || typeof current === "function") {
      const value = current as Record<string, unknown>;
      const name = String(value.name ?? "");
      const message = String(value.message ?? value.errorMessage ?? "");
      const combined = `${name} ${message} ${String(value.errorMessage ?? "")}`;
      const direct = combined.match(/FLOOD(?:_PREMIUM)?_WAIT_(\d+)/i);
      if (direct) {
        const parsed = Number(direct[1]);
        if (Number.isSafeInteger(parsed) && parsed > 0) return parsed;
      }
      const natural = combined.match(/A wait of (\d+) seconds is required/i);
      if (natural) {
        const parsed = Number(natural[1]);
        if (Number.isSafeInteger(parsed) && parsed > 0) return parsed;
      }
      const seconds = Number(value.seconds);
      if (/FloodWait|FLOOD_WAIT|SLOWMODE_WAIT/i.test(combined) && Number.isSafeInteger(seconds) && seconds > 0) return seconds;
      if (value.cause != null) pending.push(value.cause);
      if (value.originalError != null) pending.push(value.originalError);
    } else if (typeof current === "string") {
      const direct = current.match(/FLOOD(?:_PREMIUM)?_WAIT_(\d+)/i);
      if (direct) {
        const parsed = Number(direct[1]);
        if (Number.isSafeInteger(parsed) && parsed > 0) return parsed;
      }
      const natural = current.match(/A wait of (\d+) seconds is required/i);
      if (natural) {
        const parsed = Number(natural[1]);
        if (Number.isSafeInteger(parsed) && parsed > 0) return parsed;
      }
    }
  }
  return null;
}
