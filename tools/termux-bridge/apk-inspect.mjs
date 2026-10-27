import { open, readFile, stat } from "node:fs/promises";
import { homedir } from "node:os";
import { join } from "node:path";
import { inflateRawSync } from "node:zlib";

const APK_DIR = join(homedir(), "storage", "downloads", "PnS_APK");
const APK_PATHS = [
  join(APK_DIR, "base.apk"),
  join(APK_DIR, "split_config.arm64_v8a.apk"),
];
const MAX_APK_BYTES = 100 * 1024 * 1024;
const MAX_EXTRACT_BYTES = 150 * 1024 * 1024;
const MAX_REPORT = 28000;
const ASSET_PACK_PATH = join(APK_DIR, "split_abPack1.apk");
const MAX_CENTRAL_DIRECTORY = 32 * 1024 * 1024;
const MAX_ASSET_TEXT_TOTAL = 8 * 1024 * 1024;
const MAX_ASSET_METADATA_BYTES = 64 * 1024 * 1024;
const MAX_ASSET_TEXT_ENTRY = 1024 * 1024;

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

function parseIl2CppMetadata(buffer) {
  if (buffer.length < 8 || buffer.readUInt32LE(0) !== 0xfab11baf) {
    return { summary: "Metadata magic is absent; the file may be protected or not IL2CPP metadata.", strings: [] };
  }
  const version = buffer.readUInt32LE(4);
  const pairs = [];
  for (let i = 8; i + 8 <= Math.min(buffer.length, 8 + 40 * 8); i += 8) {
    pairs.push({ offset: buffer.readUInt32LE(i), size: buffer.readUInt32LE(i + 4) });
  }
  const literalTable = pairs[0];
  const literalData = pairs[1];
  const stringTable = pairs[2];
  const safeRange = section => section && section.offset <= buffer.length &&
    section.size <= buffer.length - section.offset;
  const literals = [];
  if (safeRange(literalTable) && safeRange(literalData) && literalTable.size % 8 === 0) {
    const count = Math.min(literalTable.size / 8, 200000);
    for (let i = 0; i < count; i++) {
      const p = literalTable.offset + i * 8;
      const offset = buffer.readUInt32LE(p);
      const length = buffer.readUInt32LE(p + 4);
      if (offset > literalData.size || length > literalData.size - offset || length > 4096) continue;
      const value = buffer.toString("utf8", literalData.offset + offset, literalData.offset + offset + length);
      if (value && !/[\\u0000-\\u0008\\u000b\\u000c\\u000e-\\u001f]/.test(value)) literals.push(value);
    }
  }
  const stringTableBytes = safeRange(stringTable) ? stringTable.size : 0;
  const metadataStrings = [];
  if (stringTableBytes > 0) {
    const end = stringTable.offset + stringTable.size;
    let start = stringTable.offset;
    for (let i = stringTable.offset; i < end; i++) {
      if (buffer[i] !== 0) continue;
      if (i - start >= 3 && i - start <= 512) {
        metadataStrings.push(buffer.toString("utf8", start, i));
      }
      start = i + 1;
    }
  }
  const relevantRx = /api|https?:|wss?:|socket|gateway|server|login|auth|account|player|battle|march|request|response|protobuf|messagepack|heartbeat|resource|hero|alliance|activity|token|session|endpoint|version|channel|config|command|\\bcmd\\b|\\bnet\\b|\\bhttp\\b|\\brpc\\b|\\burl\\b/i;
  const relevant = [...new Set([...literals, ...metadataStrings].filter(s => relevantRx.test(s)))].slice(0, 240);
  return {
    summary: "IL2CPP metadata: magic=0xFAB11BAF; version=" + version +
      "; headerPairs=" + pairs.length +
      "; stringLiteralRecords=" + (literalTable && literalTable.size % 8 === 0 ? literalTable.size / 8 : "invalid") +
      "; stringLiteralDataBytes=" + (literalData ? literalData.size : "n/a") +
      "; metadataStringTableBytes=" + stringTableBytes +
      "; metadataStringsDecoded=" + metadataStrings.length,
    strings: relevant,
  };
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


async function inspectAssetPack(strings) {
  let handle;
  try {
    const info = await stat(ASSET_PACK_PATH);
    if (!info.isFile()) return { summary: "Asset pack is not a regular file.", candidates: [], extracted: 0 };
    handle = await open(ASSET_PACK_PATH, "r");
    const tailSize = Math.min(65557, info.size);
    const tail = Buffer.alloc(tailSize);
    await handle.read(tail, 0, tailSize, info.size - tailSize);
    let eocd = -1;
    for (let i = tail.length - 22; i >= Math.max(0, tail.length - 65557); i--) {
      if (tail.readUInt32LE(i) === 0x06054b50) { eocd = i; break; }
    }
    if (eocd < 0) throw new Error("ZIP end-of-central-directory not found.");
    const count = tail.readUInt16LE(eocd + 10);
    const cdSize = tail.readUInt32LE(eocd + 12);
    const cdOffset = tail.readUInt32LE(eocd + 16);
    if (count === 0xffff || cdSize === 0xffffffff || cdOffset === 0xffffffff) {
      throw new Error("ZIP64 asset packs are not supported by this bounded indexer.");
    }
    if (cdSize > MAX_CENTRAL_DIRECTORY || cdOffset + cdSize > info.size) {
      throw new Error("Central directory exceeds the safe indexing limit.");
    }
    const directory = Buffer.alloc(cdSize);
    await handle.read(directory, 0, cdSize, cdOffset);
    const entries = [];
    let offset = 0;
    for (let n = 0; n < count && offset + 46 <= directory.length; n++) {
      if (directory.readUInt32LE(offset) !== 0x02014b50) break;
      const method = directory.readUInt16LE(offset + 10);
      const compressedSize = directory.readUInt32LE(offset + 20);
      const uncompressedSize = directory.readUInt32LE(offset + 24);
      const nameLen = directory.readUInt16LE(offset + 28);
      const extraLen = directory.readUInt16LE(offset + 30);
      const commentLen = directory.readUInt16LE(offset + 32);
      const localOffset = directory.readUInt32LE(offset + 42);
      if (offset + 46 + nameLen + extraLen + commentLen > directory.length) break;
      const name = directory.toString("utf8", offset + 46, offset + 46 + nameLen);
      entries.push({ name, method, compressedSize, uncompressedSize, localOffset });
      offset += 46 + nameLen + extraLen + commentLen;
    }
    const textExt = /\.(json|xml|txt|cfg|ini|lua|proto|ya?ml|csv|properties|toml|conf)$/i;
    const metadataRx = /(^|\/)global-metadata\.dat$/i;
    const candidates = entries.filter(e =>
      metadataRx.test(e.name) ||
      textExt.test(e.name) ||
      (/(config|remote|server|endpoint|network|api|environment|channel|version)/i.test(e.name) &&
       e.uncompressedSize > 0 && e.uncompressedSize <= MAX_ASSET_TEXT_ENTRY)
    );
    const extractedNames = [];
    let extractedTotal = 0;
    for (const entry of candidates.sort((a, b) =>
      Number(metadataRx.test(b.name)) - Number(metadataRx.test(a.name))
    )) {
      const isMetadata = metadataRx.test(entry.name);
      const entryLimit = isMetadata ? MAX_ASSET_METADATA_BYTES : MAX_ASSET_TEXT_ENTRY;
      const totalLimit = isMetadata ? MAX_ASSET_METADATA_BYTES : MAX_ASSET_TEXT_TOTAL;
      if (extractedNames.length >= 40 || extractedTotal >= totalLimit) {
        if (!isMetadata) break;
      }
      if (entry.uncompressedSize > entryLimit ||
          (!isMetadata && entry.uncompressedSize > MAX_ASSET_TEXT_TOTAL - Math.min(extractedTotal, MAX_ASSET_TEXT_TOTAL)) ||
          entry.compressedSize > entryLimit ||
          (!isMetadata && !textExt.test(entry.name))) continue;
      const p = entry.localOffset;
      if (p + 30 > info.size) continue;
      const header = Buffer.alloc(30);
      await handle.read(header, 0, 30, p);
      if (header.readUInt32LE(0) !== 0x04034b50) continue;
      const nameLen = header.readUInt16LE(26);
      const extraLen = header.readUInt16LE(28);
      const dataStart = p + 30 + nameLen + extraLen;
      if (dataStart + entry.compressedSize > info.size) continue;
      const compressed = Buffer.alloc(entry.compressedSize);
      await handle.read(compressed, 0, entry.compressedSize, dataStart);
      let data;
      try {
        if (entry.method === 0) data = compressed;
        else if (entry.method === 8) data = inflateRawSync(compressed, { maxOutputLength: entryLimit });
        else continue;
      } catch { continue; }
      if (data.length > entryLimit) continue;
      extractedTotal += data.length;
      extractedNames.push(entry.name + (isMetadata ? " [IL2CPP metadata]" : ""));
      for (const value of printableStrings(data)) strings.push(value);
    }
    return {
      summary: ASSET_PACK_PATH.split("/").pop() + ": " + info.size + " bytes; " + entries.length +
        " ZIP entries; central directory indexed without loading the full APK; " + extractedNames.length +
        " small text/config entries extracted (" + extractedTotal + " bytes).",
      candidates: candidates.slice(0, 100).map(e => e.name + " [" + e.uncompressedSize + " bytes]"),
      inventory: entries.slice(0, 100).map(e =>
        e.name + " [size=" + e.uncompressedSize + ", compressed=" + e.compressedSize +
        ", method=" + e.method + "]"
      ),
      extracted: extractedNames.length,
      extractedNames,
    };
  } catch (error) {
    return { summary: "Asset pack indexing note: " + String(error?.message || error), candidates: [], extracted: 0 };
  } finally {
    if (handle) await handle.close().catch(() => {});
  }
}

export async function inspectApk() {
  const strings = [];
  const inspected = [];
  const errors = [];
  let totalExtracted = 0;
  const apkSummaries = [];
  let il2cppMetadata = null;
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
      /^lib\/arm64-v8a\/[^/]+\.so$/.test(e.name) ||
      /(^|\/)global-metadata\.dat$/i.test(e.name)
    ).sort((a, b) => {
      const rank = name => {
        if (/global-metadata\.dat$/i.test(name)) return 0;
        if (/lib\/arm64-v8a\/libil2cpp\.so$/i.test(name)) return 1;
        if (/lib\/arm64-v8a\/libmain\.so$/i.test(name)) return 2;
        if (/^classes(?:\d+)?\.dex$/.test(name)) return 3;
        if (name === "AndroidManifest.xml") return 4;
        if (/\.so$/i.test(name)) return 5;
        return 6;
      };
      return rank(a.name) - rank(b.name) ||
        a.uncompressedSize - b.uncompressedSize;
    });
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
      if (/(^|\\/)global-metadata\\.dat$/i.test(entry.name)) {
        il2cppMetadata = parseIl2CppMetadata(data);
        for (const value of il2cppMetadata.strings) strings.push(value);
      }
      for (const value of printableStrings(data)) strings.push(value);
    }
  }
  const assetPack = await inspectAssetPack(strings);
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
  const networkCandidates = [...new Set(strings
    .filter(s => /puzzles|37games|firebaseio|https?:\/\/|\b(api|server|gateway|login|auth|config|endpoint|channel|region|cdn|socket|websocket|grpc|protobuf|messagepack|request|response|heartbeat|account|role|player|battle|march|alliance|rpc|url|host)\b/i.test(s))
    .map(s => s.replace(/(bearer\s+)[A-Za-z0-9._~-]+/ig, "$1[REDACTED]")
      .replace(/((?:token|secret|password|api[_-]?key|authorization)\s*[:=]\s*)[^\s,;]{6,}/ig, "$1[REDACTED]")
      .slice(0, 500)))];
  const networkScore = s => {
    const v = s.toLowerCase();
    let score = 0;
    if (/puzzles|37games|firebaseio/.test(v)) score += 100;
    if (/https?:\/\//.test(v)) score += 30;
    if (/api|server|gateway|login|auth|config|endpoint|channel|region|socket|websocket|protobuf|messagepack|heartbeat|account|role|player|battle|march|alliance|rpc/.test(v)) score += 15;
    if (/appsflyer|facebook|google|android\.com|w3\.org|example\.com|unity3d\.com|tiktok|twitter|vk\.com/.test(v)) score -= 20;
    return score;
  };
  networkCandidates.sort((a, b) => networkScore(b) - networkScore(a) || a.localeCompare(b));
  const report = [
    "APK static inspection report (refined)",
    "Files: " + apkSummaries.join(" | "),
    "Extracted bytes: " + totalExtracted,
    "Inspected entries: " + inspected.join(", "),
    "",
    "GAME / NETWORK STRING CANDIDATES (ranked; static, not proof of live API)",
    ...networkCandidates.slice(0, 160),
    "",
    "IL2CPP METADATA SUMMARY",
    il2cppMetadata ? il2cppMetadata.summary : "global-metadata.dat was not extracted.",
    "IL2CPP METADATA STRINGS (filtered; up to 160)",
    ...(il2cppMetadata ? il2cppMetadata.strings : []),
    "",
    "ASSET PACK INDEX",
    assetPack.summary,
    "Candidate resource/config names (up to 100):",
    ...assetPack.candidates,
    "",
    "ASSET PACK ZIP ENTRY INVENTORY (up to 100)",
    ...(assetPack.inventory || []),
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
    "",
    "Caveats: Static strings do not prove that an endpoint is active or public. URL templates may be assembled at runtime. This pass prioritizes global-metadata.dat and libil2cpp.so if they exist in the scanned APKs. The large asset pack is indexed by ZIP central directory and only small text/config entries are selectively extracted; binary Unity assets and other splits are not fully analyzed. No live service calls are made."
  ].join("\n").slice(0, MAX_REPORT);
  return { exitCode: 0, output: report, errorText: "" };
}
