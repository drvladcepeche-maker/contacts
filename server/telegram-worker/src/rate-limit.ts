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
