const LOCAL_APPROVAL_COMMANDS = new Set([
  "terminal_run",
  "terminal_start",
  "install_apk",
  "file_upload",
  "shizuku_file_mkdir",
  "shizuku_file_copy",
  "shizuku_file_move",
  "shizuku_file_delete",
]);

const BRAVE_MUTATING_ACTIONS = new Set(["click", "input", "open_url"]);

/**
 * Operations that can execute code, alter device state, install packages,
 * upload files, or perform consequential browser actions require a fresh
 * confirmation in the local Termux terminal.
 */
export function requiresLocalApproval(commandType, args = {}) {
  const type = String(commandType ?? "");
  if (LOCAL_APPROVAL_COMMANDS.has(type)) return true;
  if (type === "brave_accessibility") {
    return BRAVE_MUTATING_ACTIONS.has(String(args?.action ?? ""));
  }
  return false;
}
