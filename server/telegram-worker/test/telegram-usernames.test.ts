import test from "node:test";
import assert from "node:assert/strict";
import { extractTelegramUsernames, mergeTelegramUsernames } from "../src/telegram-usernames.js";

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
