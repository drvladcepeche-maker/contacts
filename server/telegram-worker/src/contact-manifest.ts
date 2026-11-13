export function filterActiveContactIds(
  candidate: { local_contact_id?: unknown; local_contact_ids?: unknown[] },
  activeIds: ReadonlySet<string>,
): string[] {
  const raw = [
    candidate.local_contact_id,
    ...(Array.isArray(candidate.local_contact_ids) ? candidate.local_contact_ids : []),
  ];
  const unique = new Set<string>();
  for (const value of raw) {
    if (typeof value !== "string" || value.length === 0 || !activeIds.has(value)) continue;
    unique.add(value);
  }
  return Array.from(unique);
}
