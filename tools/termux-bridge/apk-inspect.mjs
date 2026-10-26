import { readFile, stat } from "node:fs/promises";
import { homedir } from "node:os";
import { join } from "node:path";
import { inflateRawSync } from "node:zlib";

const APK_DIR = join(homedir(), "storage", "downloads", "PnS_APK");
const APK_PATHS = [
  join(APK_DIR, "base.apk"),
  join(APK_DIR, "split_config.arm64_v8a.apk"),
];
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
  const cleaned = raw.replace(/[),.;]+$/, "");
  try {
    const url = new URL(cleaned);
    return url.origin + (url.pathname && url.pathname !== "/" ? url.pathname : "");
  } catch { return cleaned.split(/[?#]/, 1)[0].slice(0, 250); }
}

function hostFromUrl(raw) {
  try {
    const host = new URL(raw).hostname.toLowerCase();
    if (!host || host.includes("%s") || host.includes("%") || !host.includes(".")) return null;
    if (/^(schemas\.android\.com|www\.w3\.org|ns\.adobe\.com|www\.smpte-ra\.org)$/.test(host)) return null;
    return host;
  } catch { return null; }
}

export async function inspectApk() {
  const strings = [];
  const inspected = [];
  const errors = [];
  let totalExtracted = 0;
  const apkSummaries = [];
  for (const path of APK_PATHS) {
    let info;
    try { info = await stat(path); } catch { errors.push("Not found: " + path.split("/").pop()); continue; }
    if (!info.isFile()) { errors.push("Not a file: " + path.split("/").pop()); continue; }
    if (info.size > MAX_APK_BYTES) { errors.push("Skipped over 100 MiB: " + path.split("/").pop() + " (" + info.size + " bytes)"); continue; }
    const apk = await readFile(path);
    const entries = zipEntries(apk);
    apkSummaries.push(path.split("/").pop() + ": " + info.size + " bytes; " + entries.length + " ZIP entries");
    const targets = entries.filter(e =>
      /^classes(?:\d+)?\.dex$/.test(e.name) ||
      e.name === "AndroidManifest.xml" ||
      e.name === "resources.arsc" ||
      /^lib\/arm64-v8a\/[^/]+\.so$/.test(e.name)
    );
    if (path.endsWith("base.apk") && !targets.some(e => e.name === "classes.dex")) {
      throw new Error("classes.dex not found in base.apk.");
    }
    for (const entry of targets) {
      if (totalExtracted >= MAX_EXTRACT_BYTES) break;
      if (entry.uncompressedSize > MAX_EXTRACT_BYTES - totalExtracted) continue;
      let data;
      try { data = extractEntry(apk, entry); } catch { continue; }
      if (!data) continue;
      totalExtracted += data.length;
      inspected.push(path.split("/").pop() + ":" + entry.name);
      for (const value of printableStrings(data)) strings.push(value);
    }
  }
  if (!inspected.some(name => name.startsWith("base.apk:classes.dex"))) {
    throw new Error("No DEX data extracted from base.apk.");
  }
  const all = strings.join("\n");
  const urls = new Set();
  const urlRx = /https?:\/\/[^\s"'<>\u0000-\u001f]{4,500}/gi;
  for (const match of all.matchAll(urlRx)) urls.add(scrubUrl(match[0]));
  const domains = new Set();
  for (const url of urls) {
    const host = hostFromUrl(url);
    if (host) domains.add(host);
  }
  const markers = [
    "okhttp", "retrofit", "volley", "cronet", "grpc", "websocket", "socket.io",
    "firebase", "appsflyer", "adjust", "facebook", "google analytics", "bugly",
    "unitywebrequest", "libcurl", "protobuf", "graphql", "x-api-key", "authorization",
    "content-type", "connect_timeout", "baseurl", "/api/", "/v1/", "/v2/", "/login",
    "gateway", "heartbeat", "wss://", "mqtt", "socket", "certificate pinning",
    "trustmanager", "sslcontext"
  ];
  const lower = all.toLowerCase();
  const foundMarkers = markers.filter(m => lower.includes(m));
  const report = [
    "APK static inspection report (refined)",
    "Files: " + apkSummaries.join(" | "),
    "Extracted bytes: " + totalExtracted,
    "Inspected entries: " + inspected.join(", "),
    errors.length ? "\nFILE NOTES\n" + errors.join("\n") : "",
    "",
    "HOSTS DERIVED FROM EXPLICIT URL STRINGS (" + domains.size + ")",
    ...[...domains].sort().slice(0, 100),
    "",
    "HTTP URLS (query/fragment removed; " + urls.size + ")",
    ...[...urls].sort().slice(0, 100),
    "",
    "NETWORK / SDK MARKERS",
    ...foundMarkers.sort(),
    "",
    "Caveats: Static strings do not prove that an endpoint is active or public. URL templates may be assembled at runtime. Only base.apk and split_config.arm64_v8a.apk are scanned; large asset packs and other splits are not analyzed. Native libraries are scanned only if their uncompressed size fits the extraction budget."
  ].join("\n").slice(0, MAX_REPORT);
  return { exitCode: 0, output: report, errorText: "" };
}
