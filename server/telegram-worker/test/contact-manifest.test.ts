import test from "node:test";
import assert from "node:assert/strict";
import { filterActiveContactIds } from "../src/contact-manifest.js";

test("keeps only active contact IDs and removes duplicates", () => {
  const result = filterActiveContactIds(
    { local_contact_id: "10", local_contact_ids: ["11", "12", "11", "13"] },
    new Set(["10", "11", "13"]),
  );
  assert.deepEqual(result, ["10", "11", "13"]);
});

test("uses an active duplicate when the primary ID is stale", () => {
  const result = filterActiveContactIds(
    { local_contact_id: "old-1", local_contact_ids: ["old-2", "42"] },
    new Set(["42"]),
  );
  assert.deepEqual(result, ["42"]);
});

test("returns no IDs when every contact was removed from the phone", () => {
  const result = filterActiveContactIds(
    { local_contact_id: "1", local_contact_ids: ["2", "3"] },
    new Set(["4", "5"]),
  );
  assert.deepEqual(result, []);
});
