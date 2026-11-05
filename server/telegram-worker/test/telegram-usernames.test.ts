import test from "node:test";
import assert from "node:assert/strict";
import { extractTelegramUsernames, mergeTelegramUsernames } from "../src/telegram-usernames.js";
import { calculateBatchResumeAt } from "../src/rate-limit.js";

test("extracts active GramJS usernames and the legacy primary username", () => {
  assert.equal(extractTelegramUsernames({
    username: "primary",
    usernames: [
      { username: "alias_one", active: true },
      { username: "alias_two", active: true },
      { username: "inactive_alias", active: false },
    ],
  }), "alias_one, alias_two, primary");
});

test("deduplicates aliases and removes leading at-signs", () => {
  assert.equal(extractTelegramUsernames({
    username: "@same_name",
    usernames: [{ username: "same_name", active: true }, { username: "@other", active: true }],
  }), "same_name, other");
});

test("returns null when no public username exists", () => {
  assert.equal(extractTelegramUsernames({ username: null, usernames: [] }), null);
  assert.equal(extractTelegramUsernames(undefined), null);
});

test("merges usernames only for the same Telegram ID", () => {
  assert.equal(mergeTelegramUsernames("123", "123", "old_alias, Скрыт", "new_alias"), "old_alias, new_alias");
});

test("does not carry usernames across different Telegram IDs", () => {
  assert.equal(mergeTelegramUsernames("123", "456", "old_alias", "new_alias"), "new_alias");
});

test("does not preserve stale usernames when the Telegram ID is missing", () => {
  assert.equal(mergeTelegramUsernames("123", "", "old_alias", null), null);
});

test("batch cooldown is measured from the last Telegram request", () => {
  assert.equal(
    calculateBatchResumeAt("2026-11-05T17:59:18.951Z", 0),
    "2026-11-05T18:59:18.951Z",
  );
});

test("invalid-only batches use a short fallback pause", () => {
  assert.equal(
    calculateBatchResumeAt(null, 1000),
    "1970-01-01T00:01:01.000Z",
  );
});
