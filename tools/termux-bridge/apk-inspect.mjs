import { readFile, stat } from "node:fs/promises";
import { homedir } from "node:os";
import { join } from "node:path";
import { inflateRawSync } from "node:zlib";

const APK_PATH = join(homedir(), "storage", "downloads", "PnS_APK", "base.apk");
const MAX_APK_BYTES = 100 * 1024 * 1024;
const MAX_EXTRACT_BYTES = 80 * 1024 * 1024;
const MAX_REPORT = 24000;

function printableStrings(buffer) {
  const found = [];
  let start = -1;
  for (let i = 0; i < buffer.length; i++) {
    const c = buffer[i];
    if (c >= 0x20 && c <= 0x7e) {
      if (start < 0) start = i;
    } else {
      if (start >= 0 && i - start >= 5) found.push(buffer.toString("latin1", start, i));
      start = -1;
    }
  }
  if (start >= 0 && buffer.length - start >= 5) found.push(buffer.toString("latin1", start));
  // DEX and Android resources often contain UTF-16LE strings.
  for (let i = 0; i + 10 < buffer.length; i += 2) {
    let j = i;
    while (j + 1 < buffer.length && buffer[j + 1] === 0 &&
      buffer[j] >= 0x20 && buffer[j] <= 0x7e) j += 2;
    if (j - i >= 10) found.push(buffer.toString("latin1", i, j).replace(/\x00/g, ""));
    if (j > i) i = j - 2;
  }
  return found;
}

function zipEntries(buffer) {
  const min = Math.max(0, buffer.length - 65557);
  let eocd = -1;
  for (let i = buffer.length - 22; i >= min; i--) {
    if (buffer.readUInt32LE(i) === 0x06054b50) { eocd = i; break; }
  }
  if (eocd < 0) throw new Error("APK ZIP directory not found.");
  const count = buffer.readUInt16LE(eocd + 10);
  let offset = buffer.readUInt32LE(eocd + 16);
  const entries = [];
  for (let n = 0; n < count && offset + 46 <= buffer.length; n++) {
    if (buffer.readUInt32LE(offset) !== 0x02014b50) break;
    const method = buffer.readUInt16LE(offset + 10);
    const compressedSize = buffer.readUInt32LE(offset + 20);
    const uncompressedSize = buffer.readUInt32LE(offset + 24);
    const nameLen = buffer.readUInt16LE(offset + 28);
    const extraLen = buffer.readUInt16LE(offset + 30);
    const commentLen = buffer.readUInt16LE(offset + 32);
    const localOffset = buffer.readUInt32LE(offset + 42);
    const name = buffer.toString("utf8", offset + 46, offset + 46 + nameLen);
    entries.push({ name, method, compressedSize, uncompressedSize, localOffset });
    offset += 46 + nameLen + extraLen + commentLen;
  }
  return entries;
}

function extractEntry(apk, entry) {
  const p = entry.localOffset;
  if (p + 30 > apk.length || apk.readUInt32LE(p) !== 0x04034b50) return null;
  const nameLen = apk.readUInt16LE(p + 26);
  const extraLen = apk.readUInt16LE(p + 28);
  const start = p + 30 + nameLen + extraLen;
  const end = start + entry.compressedSize;
  if (end > apk.length) return null;
  const compressed = apk.subarray(start, end);
  if (entry.method === 0) return compressed;
  if (entry.method === 8) return inflateRawSync(compressed, { maxOutputLength: MAX_EXTRACT_BYTES });
  return null;
}

function scrubUrl(raw) {
  try {
    const url = new URL(raw);
    return url.origin + (url.pathname && url.pathname !== "/" ? url.pathname : "");
  } catch { return raw.split(/[?#]/, 1)[0].slice(0, 250); }
}

export async function inspectApk() {
  const info = await stat(APK_PATH);
  if (!info.isFile()) throw new Error("Configured APK path is not a file.");
  if (info.size > MAX_APK_BYTES) throw new Error("base.apk exceeds the 100 MiB inspection limit.");
  const apk = await readFile(APK_PATH);
  const entries = zipEntries(apk);
  const targets = entries.filter(e =>
    /^classes(?:\\d+)?\\.dex$/.test(e.name) ||
    e.name === "AndroidManifest.xml" ||
    e.name === "resources.arsc"
  );
  if (!targets.some(e => e.name === "classes.dex")) throw new Error("classes.dex not found in base.apk.");
  const strings = [];
  let extracted = 0;
  const inspected = [];
  for (const entry of targets) {
    if (extracted >= MAX_EXTRACT_BYTES) break;
    if (entry.uncompressedSize > MAX_EXTRACT_BYTES - extracted) continue;
    const data = extractEntry(apk, entry);
    if (!data) continue;
    extracted += data.length;
    inspected.push(entry.name);
    strings.push(...printableStrings(data));
  }
  const all = strings.join("\n");
  const urls = new Set();
  const urlRx = /https?:\\/\\/[^\\s"'<>\\u0000-\\u001f]{4,500}/gi;
  for (const match of all.matchAll(urlRx)) urls.add(scrubUrl(match[0]));
  const domains = new Set();
  const domainRx = /(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+(?:com|net|org|io|co|cn|ru|app|dev|google|googleapis|amazonaws|cloudfront|facebook|games|xyz|me|info|tech|top)(?::\\d{1,5})?/gi;
  for (const match of all.matchAll(domainRx)) domains.add(match[0].toLowerCase());
  const markers = [
    "okhttp", "retrofit", "volley", "cronet", "grpc", "websocket", "socket.io",
    "firebase", "appsflyer", "adjust", "facebook", "google analytics", "bugly",
    "unitywebrequest", "libcurl", "protobuf", "graphql", "x-api-key", "authorization",
    "content-type", "connect_timeout", "baseurl", "/api/", "/v1/", "/v2/", "/login",
    "gateway", "heartbeat", "protobuf", "wss://", "mqtt"
  ];
  const lower = all.toLowerCase();
  const foundMarkers = markers.filter(m => lower.includes(m));
  const report = [
    "APK static inspection report",
    "File: " + APK_PATH,
    "Size: " + info.size + " bytes",
    "ZIP entries: " + entries.length,
    "Inspected entries: " + inspected.join(", "),
    "",
    "DOMAINS (" + domains.size + ")",
    ...[...domains].sort().slice(0, 180),
    "",
    "HTTP URLS (query/fragment removed; " + urls.size + ")",
    ...[...urls].sort().slice(0, 120),
    "",
    "NETWORK / SDK MARKERS",
    ...foundMarkers.sort(),
    "",
    "Caveats: strings are static indicators, not proof that an endpoint is active or a public API. Binary AndroidManifest.xml may require a dedicated AXML decoder. Native libraries and split APKs are not analyzed by this operation."
  ].join("\n").slice(0, MAX_REPORT);
  return { exitCode: 0, output: report, errorText: "" };
}
