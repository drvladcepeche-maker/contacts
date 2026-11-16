import test from "node:test";
import assert from "node:assert/strict";
import { createWorkerRunBudget } from "../src/run-budget.js";

test("stale candidates consume candidate budget but not Telegram request budget", () => {
  const budget = createWorkerRunBudget(1000, 20);

  for (let i = 0; i < 759; i += 1) budget.recordCandidate();

  assert.equal(budget.candidatesProcessed, 759);
  assert.equal(budget.telegramRequestsMade, 0);
  assert.equal(budget.canMakeTelegramRequest(), true);

  for (let i = 0; i < 20; i += 1) {
    assert.equal(budget.canMakeTelegramRequest(), true);
    budget.recordTelegramRequest();
    budget.recordCandidate();
  }

  assert.equal(budget.telegramRequestsMade, 20);
  assert.equal(budget.canMakeTelegramRequest(), false);
  assert.equal(budget.canProcessCandidate(), true);
});

test("candidate and Telegram request limits are independent", () => {
  const budget = createWorkerRunBudget(3, 2);

  budget.recordCandidate();
  budget.recordCandidate();
  budget.recordTelegramRequest();
  budget.recordTelegramRequest();

  assert.equal(budget.canProcessCandidate(), true);
  assert.equal(budget.canMakeTelegramRequest(), false);

  budget.recordCandidate();
  assert.equal(budget.canProcessCandidate(), false);
  assert.equal(budget.telegramRequestsMade, 2);
});

test("budget values are normalized to positive integer limits", () => {
  const budget = createWorkerRunBudget(0, -5);
  assert.equal(budget.canProcessCandidate(), true);
  assert.equal(budget.canMakeTelegramRequest(), true);
});
