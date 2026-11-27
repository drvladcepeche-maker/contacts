# MXQ PRO 4K — Osiris Agent node profile

**Profile version:** 1.0  
**Captured:** 2026-11-27  
**Status:** field-verified baseline; per-device, not a generic MXQ specification.

## Purpose

Reference hardware/software profile for integrating this particular MXQ PRO 4K into the Osiris ecosystem. Treat every additional TV box as a separate edge node with its own hardware inventory, device registration, token, network behavior, boot tests and recovery record. Do not infer compatibility from the commercial model name alone.

## Hardware

| Field | Observed value |
|---|---|
| Commercial model | MXQ PRO 4K |
| Android device property | `rk3128_box` |
| SoC / platform | Rockchip RK3128 |
| Device Tree compatible | `rockchip,rk3128` |
| Hardware property | `rk30board` |
| CPU | 4 × ARM Cortex-A7 |
| ABI | ARMv7 |
| Physical RAM | 1 GiB (confirmed by boot log: 1024 MB total) |
| ZRAM | 533,413,888 bytes (~509 MiB) |
| Kernel | Linux 3.10.104 |
| Wi-Fi | Rockchip RK915, SDIO |
| Wi-Fi driver | `rk915_sdio`, module `rk915` |
| Wi-Fi capability | 2.4 GHz 802.11b/g/n, 1T1R, 20 MHz, up to 65 Mbps |
| Ethernet MAC | `68:1d:ef:22:33:06` |
| Latest observed free `/data` | ~164 MiB (dynamic; remeasure) |

**Memory caveat:** kernel `/proc/meminfo` and `dumpsys meminfo` report an implausible ~64 GiB. Do not use those values. Physical 1 GiB is confirmed by boot log (1024 MB total, 963108 KiB available, 85468 KiB reserved).

## Android build

| Field | Value |
|---|---|
| Android version | 7.1.2 |
| API level | 25 |
| Build ID | `TVBOX` |
| Build type / tags | `userdebug` / `test-keys` |
| `ro.debuggable` | 1 |
| `ro.secure` | 1 |
| `ro.adb.secure` | 0 |
| Security patch | 2017-04-05 |
| Fingerprint | `Android/rk3128_box/rk3128_box:7.1.2/TVBOX/02131058:userdebug/test-keys` |
| Build date | 2023-02-13 |
| Incremental | `eng.20230213.105850` |

The property `ro.build.version.release` misleadingly reports `12.0`; API 25 and fingerprint establish Android 7.1.2. Shell UID is 2000; root has not been obtained. `adb root` has not been tested or enabled.

## Network characteristics

### Wi-Fi
- RK915 is connected over SDIO (`mmc2:0001:1`).
- Driver version observed: `2021-03-05-11:09:41.9445c291.Wei`; build time 2022-02-15.
- Last healthy sample: 2412 MHz / channel 1, RSSI about -58 dBm, 65 Mbps link, zero RX/TX errors and drops.
- Wi-Fi MAC changes after reboot. Boot log records failure to read factory MAC from vendor storage and driver-generated random MAC. Do not rely on DHCP reservations tied to Wi-Fi MAC.
- Boot log did not find `rk915_cal.bin` or `rk915_rf_para.txt`, but firmware and patch loaded. Do not copy arbitrary calibration blobs: they may be board-specific.

### Ethernet and addressing
- Ethernet MAC: `68:1d:ef:22:33:06`; earlier DHCP lease: `192.168.1.231`.
- Wi-Fi address changed from `192.168.1.211` to `192.168.1.174` after power cycle. These are historical observations, not static addresses.
- Keep Ethernet available until Wi-Fi-only reboot, agent launch, ADB recovery and heartbeat continuity are repeatedly verified.

## Osiris Agent software stack

| Component | Configuration |
|---|---|
| Android terminal | Termux `0.119.0-beta.3` (Android 7 / ARMv7 build) |
| Boot helper | Termux:Boot `0.8.1` |
| Runtime | Node.js `v24.22.3` ARMv7 |
| Node flags | `--jitless --max-old-space-size=48 --max-semi-space-size=2` |
| Agent home | `~/osiris-agent/` |
| Bridge client | `~/osiris-agent/tools/termux-bridge/bridge.mjs` |
| Boot script | `~/.termux/boot/start-osiris-agent` |
| Token path | `~/.config/termux-bridge/device-token` (secret; never copy into docs/logs) |
| Config directory permissions | 0700 |
| Token file permissions | 0600 |

### Server registration
- Supabase project: `moi-kontakty`.
- Project ID: `xevqbgjtxuveeqaiacvm`.
- Device label: `MXQ PRO 4K — Osiris Agent`.
- Device row ID: `f20f907b-30d4-4d96-b755-165cded4dd96`.
- Server function: `termux-bridge`, repository `drvladcepeche-maker/contacts`, branch `feature/brave-accessibility-control`, path `supabase/functions/termux-bridge/index.ts`.
- Client: `tools/termux-bridge/bridge.mjs`.
- This node has its own device row and token. Do not reuse them for another device. Never place token contents in a profile, issue, commit or log.

## External supervisor and discovery

Debian systemd unit: `osiris-mxq-bootstrap.service`; script: `/usr/local/sbin/osiris-mxq-bootstrap`.

- Uses a dedicated ADB server on port 5038; do not disturb the main ADB server on 5037.
- Tries known addresses, then scans DHCP range `192.168.1.100–192.168.1.249` for TCP/5555.
- Validates device identity (device/model/fingerprint and a locally held serial hash; do not publish the serial or hash).
- Checks Android boot completion and Node process.
- If Node is absent, launches Termux and `~/.termux/boot/start-osiris-agent`.
- Runs periodic recovery checks.

ADB TCP/5555 is unauthenticated (`ro.adb.secure=0`). Keep it restricted to a trusted LAN and firewall; never expose it to the internet or port-forward it. Primary remote control is the agent's outbound HTTPS connection to Supabase. ADB is a secondary LAN recovery channel.

## Verified tests and current confidence

### Before reboot
- Node agent was running and heartbeat updated in Supabase.
- ADB over Wi-Fi worked.
- Termux wake lock was active.
- Termux was in the device-idle whitelist; device idle state was ACTIVE.
- Lock screen was disabled and initial Android setup was complete.
- Agent commands execute as Termux UID, not root.

### Full power-cycle recovery (2026-11-27)
1. A controlled reboot interrupted heartbeat and the device did not return at its old addresses.
2. Power was physically removed for about 15 seconds, then restored.
3. Android completed boot (`sys.boot_completed=1`).
4. Wi-Fi reconnected with a different IP address (`192.168.1.174`).
5. Debian supervisor found the device at the new address.
6. Supervisor observed Node missing and launched Termux.
7. Node process appeared (PID 1224 at the time of observation).
8. Supabase heartbeat resumed; the observed heartbeat age was about 7 seconds.
9. A successful terminal audit job returned `MXQ_DEVICE_AUDIT_OK`, `DEVICE_PROFILE_OK`, `PACKAGE_INVENTORY_OK`, exit code 0.

**Conclusion:** end-to-end recovery after full power removal was verified with the external Debian supervisor. This does **not** yet prove that Termux:Boot independently launches the agent. The supervisor's role must be separated in a later test.

## Firmware and root policy

Do not flash or modify system partitions yet. No public image has been verified against this exact PCB, RK915 module, device tree, partition layout, persistent root/ADB-root behavior and a complete recovery path. Before any firmware experiment require:
1. exact PCB revision and component inventory;
2. full NAND/eMMC backup;
3. verified recovery/restore method;
4. matching RK915 and device tree;
5. demonstrated persistent root after reboot if root is actually needed.

Do not enable `adb root` while unauthenticated ADB is reachable on an untrusted network. No OpenWrt supervisor migration or additional OpenWrt packages are part of this profile.

## Device-resident inventory

Files under `~/osiris-agent/`:
- `device-profile.json` — device profile;
- `INTEGRATION-STATUS.md` — integration state, tests and caveats;
- `package-inventory.txt` — Android package inventory.

## Open items for this node

1. Verify Termux:Boot independently, without Debian supervisor launching Termux.
2. Verify agent startup and sustained Supabase heartbeat after that isolated boot test.
3. Test Wi-Fi loss/reassociation and recovery.
4. Review `agent.log` growth and rotation.
5. Reassess root only against a concrete requirement and safe ADB isolation.
6. Do not flash firmware without the evidence and recovery prerequisites above.

## Onboarding checklist for every additional Osiris edge device

Create a new profile and separate server device registration. Record:
- exact model and PCB marking/photo;
- SoC, architecture, CPU, physical RAM and ZRAM;
- Android/API, kernel, fingerprint, build type and security patch;
- Wi-Fi chipset/interface/driver, MAC behavior, bands and stability;
- Ethernet chipset/MAC and interfaces;
- storage capacity and free space;
- Termux, Termux:Boot, Node version and ABI;
- separate device row ID and token storage location (never token value);
- discovery method and addressing behavior;
- independent results for boot, Wi-Fi reconnect, agent launch, heartbeat, command execution and watchdog recovery;
- root/ADB/firmware/recovery constraints.

Use per-test states `not_tested`, `passed`, `failed`, `blocked`. Never transfer one device's test results to another, even if the commercial model matches.

---
Snapshot date: 2026-11-27. IP addresses, free storage, PIDs, RSSI and heartbeat age are transient measurements, not fixed hardware properties.
