import { createInterface } from "node:readline/promises";
import { stdin as input, stdout as output } from "node:process";
import { TelegramClient } from "telegram";
import { StringSession } from "telegram/sessions/index.js";

const apiId = Number(process.env.TELEGRAM_API_ID);
const apiHash = process.env.TELEGRAM_API_HASH?.trim();

if (!Number.isInteger(apiId) || apiId <= 0 || !apiHash) {
  console.error("Set TELEGRAM_API_ID and TELEGRAM_API_HASH in this Termux session first.");
  process.exit(1);
}

const rl = createInterface({ input, output });
const ask = async (question: string) => (await rl.question(question)).trim();

let client: TelegramClient | undefined;
try {
  const phoneNumber = await ask("Telegram phone number (international format, e.g. +...): ");
  if (!/^\+[1-9]\d{7,14}$/.test(phoneNumber)) {
    throw new Error("Phone number must be in international format, e.g. +12345678900.");
  }

  client = new TelegramClient(new StringSession(""), apiId, apiHash, {
    connectionRetries: 3,
    autoReconnect: true,
  });

  await client.start({
    phoneNumber: async () => phoneNumber,
    phoneCode: async () => await ask("Enter the login code Telegram sent you: "),
    password: async () => await ask("Enter Telegram 2-step verification password (if enabled): "),
    onError: (error) => console.error("Telegram authorization error:", error.message),
  });

  if (!(await client.checkAuthorization())) {
    throw new Error("Telegram did not confirm authorization.");
  }

  console.log("\nAuthorization successful.");
  console.log("Copy the following value into the Hostless secret TELEGRAM_SESSION.");
  console.log("Treat it like a password; do not send it to anyone or commit it to Git.");
  console.log("\nTELEGRAM_SESSION=" + (client.session as StringSession).save());
} catch (error) {
  console.error("Session generation failed:", error instanceof Error ? error.message : String(error));
  process.exitCode = 1;
} finally {
  if (client) await client.disconnect().catch(() => {});
  rl.close();
}
