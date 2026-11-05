export type TelegramUserLike = {
  username?: unknown;
  usernames?: unknown;
};

function normalizeUsernameValues(value: unknown): string[] {
  const values = typeof value === "string" ? value.split(",") : [];
  return values
    .map((item) => item.trim().replace(/^@/, ""))
    .filter((item) => item && item.toUpperCase() !== "СКРЫТ");
}

export function extractTelegramUsernames(user: TelegramUserLike | null | undefined): string | null {
  const entries = Array.isArray(user?.usernames) ? user.usernames : [];
  const active = entries
    .filter((entry: any) => entry && typeof entry.username === "string" && entry.active !== false)
    .map((entry: any) => entry.username as string);
  const legacy = typeof user?.username === "string" ? [user.username] : [];
  const names = [...active, ...legacy]
    .map((value) => String(value).trim().replace(/^@/, ""))
    .filter(Boolean);
  return [...new Set(names)].join(", ") || null;
}

export function mergeTelegramUsernames(
  existingTelegramId: string,
  nextTelegramId: string,
  existingValue: unknown,
  incomingValue: unknown,
): string | null {
  const previous = existingTelegramId && existingTelegramId === nextTelegramId
    ? normalizeUsernameValues(existingValue)
    : [];
  const incoming = normalizeUsernameValues(incomingValue);
  return [...new Set([...previous, ...incoming])].join(", ") || null;
}
