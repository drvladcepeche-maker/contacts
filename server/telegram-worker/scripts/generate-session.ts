import { createInterface } from "node:readline/promises";
import { stdin as input, stdout as output } from "node:process";
import { mkdir, chmod, writeFile, access } from "node:fs/promises";
import { constants } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";
import { TelegramClient } from "telegram";
import { StringSession } from "telegram/sessions/index.js";

const force = process.argv.includes("--force");
const sessionDir = join(homedir(), ".config", "contacts-telegram");
const sessionPath = join(sessionDir, "TELEGRAM_SESSION");
const rl = createInterface({ input, output });
const ask = async (prompt: string) => (await rl.question(prompt)).trim();
let client: TelegramClient | undefined;

async function fileExists(path: string): Promise<boolean> {
  try {
    await access(path, constants.F_OK);
    return true;
  } catch {
    return false;
  }
}

/** Always ask step-by-step so values can be typed from my.telegram.org. */
async function resolveCredentials(): Promise<{ apiId: number; apiHash: string }> {
  console.log("\nОткройте https://my.telegram.org/apps и вводите значения по одному.\n");

  const apiIdRaw = await ask("1/5  App api_id (только цифры): ");
  const apiId = Number(apiIdRaw.replace(/\s+/g, ""));
  if (!Number.isInteger(apiId) || apiId <= 0) {
    throw new Error("api_id должен быть положительным целым числом.");
  }
  console.log(`   OK: api_id принят (длина ${String(apiId).length} цифр).\n`);

  const apiHash = (await ask("2/5  App api_hash (строка из кабинета): ")).replace(/\s+/g, "");
  if (apiHash.length < 16) {
    throw new Error(`api_hash слишком короткий (длина ${apiHash.length}). Проверьте копирование.`);
  }
  console.log(`   OK: api_hash принят (длина ${apiHash.length}).\n`);

  return { apiId, apiHash };
}

try {
  if (await fileExists(sessionPath)) {
    if (!force) {
      const answer = await ask(
        `Файл сессии уже есть:\n  ${sessionPath}\n\nПерезаписать? (y/N): `
      );
      if (!/^y(es)?$/i.test(answer)) {
        console.log("Отменено. Старая сессия не изменена.");
        process.exit(0);
      }
    } else {
      console.log(`--force: перезапись ${sessionPath}`);
    }
  }

  const { apiId, apiHash } = await resolveCredentials();

  const phone = await ask("3/5  Номер телефона Telegram (+79001234567): ");
  if (!/^\+[1-9]\d{7,14}$/.test(phone)) {
    throw new Error("Номер должен быть в международном формате, например +79001234567");
  }
  console.log("   OK: номер принят.\n");

  client = new TelegramClient(new StringSession(""), apiId, apiHash, {
    connectionRetries: 8,
    autoReconnect: true,
    useWSS: false,
  });

  await client.start({
    phoneNumber: async () => phone,
    phoneCode: async () => {
      const code = await ask("4/5  Код из Telegram: ");
      if (!code) throw new Error("Код не введён");
      return code;
    },
    password: async () => {
      const password = await ask("5/5  Пароль 2FA (если нет — просто Enter): ");
      return password;
    },
    onError: (error) => console.error("Ошибка Telegram:", error.message),
  });

  if (!(await client.checkAuthorization())) {
    throw new Error("Авторизация Telegram не подтверждена.");
  }

  const session = (client.session as StringSession).save();
  await mkdir(sessionDir, { recursive: true, mode: 0o700 });
  await chmod(sessionDir, 0o700);
  await writeFile(sessionPath, session + "\n", { encoding: "utf8", mode: 0o600, flag: "w" });
  await chmod(sessionPath, 0o600);

  console.log("\nГотово. Авторизация успешна.");
  console.log("Сессия сохранена (значение не печатается):");
  console.log(sessionPath);
  console.log("\nДальше:");
  console.log("  cat " + sessionPath);
  console.log("  → вставьте строку в GitHub Secret TELEGRAM_SESSION");
  console.log("  → обновите Secrets TELEGRAM_API_ID и TELEGRAM_API_HASH теми же значениями");
  console.log("  → Run workflow Telegram Resolver Worker");
  console.log("Не отправляйте сессию в чат.");
} finally {
  if (client) await client.disconnect().catch(() => {});
  rl.close();
}
