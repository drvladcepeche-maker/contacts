import test from "node:test";
import assert from "node:assert/strict";
import { adaptiveRequestIntervalMs, calculateBatchResumeAt, calculateFloodWaitResumeAt, floodWaitSeconds } from "../src/rate-limit.js";

test("batch cooldown is measured from the last Telegram request", () => {
  assert.equal(calculateBatchResumeAt("2026-11-05T17:59:18.951Z", 0), "2026-11-05T18:59:18.951Z");
});
test("invalid-only batches use a short fallback pause", () => {
  assert.equal(calculateBatchResumeAt(null, 1000), "1970-01-01T00:01:02.000Z");
});
test("adaptive interval preserves the configured baseline and escalates after FloodWait", () => {
  assert.equal(adaptiveRequestIntervalMs(5_000, 0), 5_000);
  assert.equal(adaptiveRequestIntervalMs(5_000, 1), 65_000);
  assert.equal(adaptiveRequestIntervalMs(5_000, 2), 125_000);
  assert.equal(adaptiveRequestIntervalMs(5_000, 10), 300_000);
});
test("FloodWait resume includes a one-minute safety margin", () => {
  assert.equal(calculateFloodWaitResumeAt(45, 1000), "1970-01-01T00:01:46.000Z");
});
test("invalid FloodWait durations use a safe minimum pause", () => {
  assert.equal(calculateFloodWaitResumeAt(0, 1000), "1970-01-01T00:01:02.000Z");
  assert.equal(calculateFloodWaitResumeAt(-5, 1000), "1970-01-01T00:01:02.000Z");
});
test("detects FloodWait from server error messages", () => {
  assert.equal(floodWaitSeconds(new Error("FLOOD_WAIT_59645")), 59645);
  assert.equal(floodWaitSeconds({ message: "A wait of 90 seconds is required" }), 90);
});
test("detects FloodWait from the typed GramJS seconds property", () => {
  assert.equal(floodWaitSeconds({ name: "FloodWaitError", seconds: 75 }), 75);
});
test("detects FloodWait in nested causes", () => {
  assert.equal(floodWaitSeconds({ cause: { name: "FloodWaitError", seconds: 120 } }), 120);
});
test("does not treat unrelated or invalid errors as FloodWait", () => {
  assert.equal(floodWaitSeconds({ name: "TimeoutError", seconds: 30 }), null);
  assert.equal(floodWaitSeconds({ name: "FloodWaitError", seconds: 0 }), null);
});
test("handles circular error causes without looping", () => {
  const error: { name: string; cause?: unknown } = { name: "WrapperError" };
  error.cause = error;
  assert.equal(floodWaitSeconds(error), null);
});
