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

export function adaptiveRequestIntervalMs(
  baseIntervalMs: number,
  floodWaitCount = 0,
  maxIntervalMs = 5 * 60_000,
): number {
  const safeBase = Math.max(60_000, baseIntervalMs);
  const escalation = Math.max(0, Math.floor(floodWaitCount)) * 60_000;
  return Math.min(maxIntervalMs, safeBase + escalation);
}

export function calculateFloodWaitResumeAt(
  waitSeconds: number,
  nowMs = Date.now(),
): string {
  const safeWaitSeconds = Math.max(1, Math.floor(waitSeconds));
  // Add a one-minute margin to avoid resuming at the exact server boundary.
  return new Date(nowMs + (safeWaitSeconds + 60) * 1000).toISOString();
}

export function floodWaitSeconds(error: unknown): number | null {
  const pending: unknown[] = [error];
  const seen = new Set<unknown>();

  while (pending.length > 0) {
    const current = pending.shift();
    if (current == null || seen.has(current)) continue;
    seen.add(current);

    if (typeof current === "object" || typeof current === "function") {
      const value = current as Record<string, unknown>;
      const name = String(value.name ?? "");
      const message = String(value.message ?? value.errorMessage ?? "");
      const combined = `${name} ${message} ${String(value.errorMessage ?? "")}`;

      const direct = combined.match(/FLOOD(?:_PREMIUM)?_WAIT_(\d+)/i);
      if (direct) return Number(direct[1]);

      const natural = combined.match(/A wait of (\d+) seconds is required/i);
      if (natural) return Number(natural[1]);

      const seconds = Number(value.seconds);
      if (/FloodWait|FLOOD_WAIT|SLOWMODE_WAIT/i.test(combined) && Number.isFinite(seconds) && seconds > 0) {
        return Math.floor(seconds);
      }

      if (value.cause != null) pending.push(value.cause);
      if (value.originalError != null) pending.push(value.originalError);
    } else if (typeof current === "string") {
      const direct = current.match(/FLOOD(?:_PREMIUM)?_WAIT_(\d+)/i);
      if (direct) return Number(direct[1]);
      const natural = current.match(/A wait of (\d+) seconds is required/i);
      if (natural) return Number(natural[1]);
    }
  }

  return null;
}
