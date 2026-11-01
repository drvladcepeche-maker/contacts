import { createInterface } from "node:readline/promises";
import { stdin as input, stdout as output } from "node:process";
import { mkdir, chmod, writeFile } from "node:fs/promises";
import { homedir } from "node:os";
import { join } from "node:path";
import { TelegramClient } from "telegram";
import { StringSession } from "telegram/sessions/index.js";

const apiId = Number(process.env.TELEGRAM_API_ID);
const apiHash = process.env.TELEGRAM_API_HASH?.trim();
if (!Number.isInteger(apiId) || apiId <= 0 || !apiHash) {
  throw new Error("Set TELEGRAM_API_ID and TELEGRAM_API_HASH in the local environment.");
}

const sessionDir = join(homedir(), ".config", "contacts-telegram");
const sessionPath = join(sessionDir, "TELEGRAM_SESSION");
const rl = createInterface({ input, output });
const ask = async (prompt: string) => (await rl.question(prompt)).trim();
let client: TelegramClient | undefined;

try {
  const phone = await ask("Telegram account phone (international format): ");
  if (!/^\\+[1-9]\\d{7,14}$/.test(phone)) throw new Error("Invalid international phone number.");

  client = new TelegramClient(new StringSession(""), apiId, apiHash, {
    connectionRetries: 3,
    autoReconnect: true,
  });

  await client.start({
    phoneNumber: async () => phone,
    phoneCode: async () => ask("Telegram login code: "),
    password: async () => ask("Telegram 2FA password (if enabled): "),
    onError: error => console.error("Telegram authorization error:", error.message),
  });

  if (!(await client.checkAuthorization())) {
    throw new Error("Telegram authorization was not confirmed.");
  }

  const session = (client.session as StringSession).save();
  await mkdir(sessionDir, { recursive: true, mode: 0o700 });
  await chmod(sessionDir, 0o700);

  // wx prevents accidentally overwriting an existing authorized session.
  await writeFile(sessionPath, session + "\\n", { encoding: "utf8", mode: 0o600, flag: "wx" });
  await chmod(sessionPath, 0o600);

  console.log("\\nTelegram authorization successful.");
  console.log("Session saved locally with restricted file permissions:");
  console.log(sessionPath);
  console.log("\\nThe session value was NOT printed. Copy it locally into GitHub Actions secret TELEGRAM_SESSION.");
  console.log("Never send it in chat, commit it, or expose it in terminal logs.");
} finally {
  if (client) await client.disconnect().catch(() => {});
  rl.close();
}
