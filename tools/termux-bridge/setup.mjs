import { createHash, randomBytes } from "node:crypto";
import { mkdirSync, writeFileSync, existsSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";

const dir = join(homedir(), ".config", "termux-bridge");
const tokenPath = join(dir, "device-token");
mkdirSync(dir, { recursive: true, mode: 0o700 });
if (existsSync(tokenPath)) {
  console.error("A device token already exists. Refusing to replace it.");
  console.error("If you are rotating a token, revoke the old device first.");
  process.exit(1);
}
const token = randomBytes(32).toString("hex");
const tokenHash = createHash("sha256").update(token).digest("hex");
writeFileSync(tokenPath, token + "\n", { mode: 0o600, flag: "wx" });
console.log("Local device token created at:", tokenPath);
console.log("The raw token was not printed.");
console.log("\nCopy ONLY this hash into Supabase SQL Editor:");
console.log(tokenHash);
console.log("\nThen run this SQL, replacing HASH_VALUE with the hash above:");
console.log("insert into public.termux_bridge_devices (label, token_hash, enabled) values ('Android Termux', 'HASH_VALUE', true);");
