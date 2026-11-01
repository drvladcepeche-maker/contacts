import { createInterface } from "node:readline/promises";
import { stdin as input, stdout as output } from "node:process";
import { TelegramClient } from "telegram";
import { StringSession } from "telegram/sessions/index.js";

const apiId = Number(process.env.TELEGRAM_API_ID);
const apiHash = process.env.TELEGRAM_API_HASH?.trim();
if (!Number.isInteger(apiId) || apiId <= 0 || !apiHash) {
  throw new Error("Set TELEGRAM_API_ID and TELEGRAM_API_HASH in the local environment.");
}
const rl = createInterface({ input, output });
const ask = async (prompt: string) => (await rl.question(prompt)).trim();
let client: TelegramClient | undefined;
try {
  const phone = await ask("Dedicated Telegram account phone (international format): ");
  if (!/^\+[1-9]\d{7,14}$/.test(phone)) throw new Error("Invalid international phone number.");
  client = new TelegramClient(new StringSession(""), apiId, apiHash, { connectionRetries: 3, autoReconnect: true });
  await client.start({
    phoneNumber: async () => phone,
    phoneCode: async () => ask("Telegram login code: "),
    password: async () => ask("2FA password (if enabled): "),
    onError: error => console.error("Telegram authorization error:", error.message),
  });
  if (!(await client.checkAuthorization())) throw new Error("Telegram authorization was not confirmed.");
  console.log("\nAuthorization successful. Add this value only to the GitHub repository secret TELEGRAM_SESSION:");
  console.log((client.session as StringSession).save());
  console.log("\nDo not paste this session into chat, commit it, or include it in logs.");
} finally {
  if (client) await client.disconnect().catch(() => {});
  rl.close();
}
