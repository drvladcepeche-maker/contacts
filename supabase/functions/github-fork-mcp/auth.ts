export function isAllowedGithubForkUser(userId: string, allowedUserId: string | undefined | null): boolean {
  const expected = allowedUserId?.trim() ?? "";
  return expected.length > 0 && userId.length > 0 && userId === expected;
}
