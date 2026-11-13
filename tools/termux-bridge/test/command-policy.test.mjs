import test from "node:test";
import assert from "node:assert/strict";
import { requiresLocalApproval } from "../command-policy.mjs";

test("remote shell and interactive input require local approval", () => {
  for (const type of ["terminal_run", "terminal_start"]) {
    assert.equal(requiresLocalApproval(type), true, type);
  }
});

test("device mutations and file uploads require local approval", () => {
  for (const type of [
    "install_apk", "file_upload", "shizuku_file_mkdir",
    "shizuku_file_copy", "shizuku_file_move", "shizuku_file_delete",
  ]) {
    assert.equal(requiresLocalApproval(type), true, type);
  }
});

test("read-only and observation commands do not require approval", () => {
  for (const type of ["pwd", "list_files", "file_read", "apk_inventory", "terminal_read", "terminal_stop", "terminal_input"]) {
    assert.equal(requiresLocalApproval(type), false, type);
  }
  for (const action of ["snapshot", "back", "scroll"]) {
    assert.equal(requiresLocalApproval("brave_accessibility", { action }), false, action);
  }
});

test("consequential Brave actions require local approval", () => {
  for (const action of ["click", "input", "open_url"]) {
    assert.equal(requiresLocalApproval("brave_accessibility", { action }), true, action);
  }
});
