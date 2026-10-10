**[简体中文](README.md) | English**

# LanAudioStream — LAN-only audio streaming (Windows → Android)

Stream the **system audio** playing on Windows 11 to an Android phone on the same local network, with low latency and high quality.
The desktop app captures the system mix via **WASAPI Loopback**, encodes it with **Opus**, and transports it over a self-designed **UDP protocol (LAS1)**;
the phone plays it with **AudioTrack** while drawing live latency / bitrate / speed / loss / jitter charts and computing L/R loudness meters locally.

> **AI-generated project**: **all of the code, tests, documentation and build scripts in this repository were produced by an AI coding agent (DSH, DeepSeek Harness)**.
> The human participant only defines requirements, reports bugs found during real use, and re-verifies fixes on a real device. See **§10**.

```
┌────────────────────── Windows 11 (desktop) ──────────────────────┐
│ WASAPI Loopback ─► 48 kHz/2ch float ─► Opus (10 ms) ─► UDP 47655 │
│   ▲ optional: mute the local speakers (the stream continues)     │
└────────────────────────────┬─────────────────────────────────────┘
                             │  UDP (same LAN, no relay server)
┌────────────────────────────▼─────────────────────────────────────┐
│ discovery (47654 broadcast) ─► handshake ─► jitter buffer ─►     │
│ Opus decode ─► AudioTrack   |   foreground service + charts      │
└──────────────────────────────────────────────────────────────────┘
```

## 1. Features

**Desktop (Windows 11)**
- Captures **system playback audio** (WASAPI Loopback, event-driven, 10–20 ms buffers, works even while the endpoint is muted)
- **Can mute the local speakers**: uses `IAudioEndpointVolume::SetMute`; capture is unaffected (verified — see §5)
- The status panel shows only metrics the desktop can obtain by itself: clients, session state, current format, capture device, capture+encode latency, send bitrate / packet rate / total bytes
- **Settings are committed manually**: edits only touch the local UI until **Save settings** is clicked, which also pushes them to the phone; **Reset all settings** restores defaults
- Bitrate 64–320 kbps, frame size 5/10/20/40 ms, packet-loss redundancy toggle, configurable ports; UDP broadcast discovery (no manual IP entry), device name advertised as `LanAudioStream@<computer name>`; built-in headless self-test mode and a protocol test client (LoopbackClient.exe)

**Phone (Android 8.0+, APK ~0.8 MB)**
- **Automatic discovery + connection**, exponential-backoff reconnect (300 ms → 4 s); both ends stop periodic probing once connected (saves power) and resume after a drop; **background playback** backed by a foreground service, notification metrics and WakeLock / WifiLock / MulticastLock
- Live metrics: end-to-end latency, jitter, receive bitrate, instantaneous speed, loss rate, lost/late frame counts, jitter buffer, audio output buffer, decoded frames, PLC concealment, playback underruns, clock-sync RTT
- **Live charts** (above the settings screen; 5 curves drawn on a custom Canvas, no third-party dependencies) and **L/R loudness meters**: both are refreshed at display rate from ring buffers with zero per-frame allocations; loudness is computed on the phone from decoded PCM and never depends on data sent back by the desktop
- **Settings are saved manually**: audio format (Opus / **lossless PCM**), sample rate 48/44.1/32/24/16 kHz, bitrate, frame size (PCM capped at 10 ms), channels, redundancy, mute desktop speakers, capture buffer 10–50 ms, DTX — these take effect **on the desktop**; disconnect timeout 3–600 s, jitter-buffer strategy, auto reconnect, metrics refresh rate 1–60 Hz, notification metrics — these take effect **locally**; plus a server list for manually picking among multiple desktops

## 2. Repository layout

```
LanAudioStream/
├─ desktop/                        Windows desktop app (C# / .NET 10 / WinForms, includes LanAudioStream.sln)
│  ├─ src/LanAudioStream.Core/     protocol, Opus encoding, UDP server, broadcast discovery (testable cross-platform)
│  ├─ src/LanAudioStream.Desktop/  WinForms UI, WASAPI capture, encoding pipeline, mute control
│  └─ tools/LanAudioStream.LoopbackClient/  protocol conformance / self-test client (simulates a phone)
├─ android/                        Android app (Kotlin + pure-Java core)
│  ├─ app/src/main/java/com/lasaudio/{core,app}/  core (discovery/session/jitter buffer/Opus decode, pure Java, testable on a JVM) and app (MainActivity / foreground service / AudioTrack / charts)
│  ├─ tools/CoreProbe.java         JVM-side core-logic tester (verifies the receive chain without a real device)
│  └─ keystore/lan-audio.jks       APK signing key (generated locally, never committed)
├─ scripts/                        build and self-test scripts
├─ docs/                           protocol / build / usage documentation / changelog
└─ dist/                           artifact folder (exe/apk are not committed and ship via Releases; only SHA256SUMS.txt is committed)
```

> All files under `docs/` are currently written in Chinese.

## 3. Quick start

### 3.1 Desktop

Download `LanAudioStream-1.0.0-win-x64.exe` from **[Releases](https://github.com/XiaoliuNya/LanAudioStream/releases/latest)** (self-contained single file, no .NET installation required) and double-click it. Or build it from source (requires the .NET 10 SDK):

```powershell
scripts\build_desktop.ps1            # output: dist\LanAudioStream-1.0.0-win-x64.exe
```
1. Pick the default playback device (recommended); to silence the PC, tick **Mute computer speakers** and click **Save settings** (streaming continues while muted)
2. Click **▶ Start streaming**
3. If Windows Firewall prompts on first run, allow it on private networks (or run `scripts\allow_firewall.ps1`); afterwards, every audio change only takes effect after clicking **Save settings**, which also syncs it to the phone, and **Reset all settings** restores the defaults

### 3.2 Phone

Download `LanAudioStream-1.0.0.apk` from **[Releases](https://github.com/XiaoliuNya/LanAudioStream/releases/latest)**, copy it to the phone and tap it to install (you must allow installing from unknown sources), or use adb:

```powershell
adb install -r LanAudioStream-1.0.0.apk
```
1. Put the phone and the PC on the same WiFi, open the app and tap **Start receiving** (the app discovers and connects to the desktop automatically)
2. Audio parameters live under "Audio settings (applied on the PC)" and **only take effect after tapping Save settings**; items under "Playback settings (this device)" apply immediately

> No manual pairing is required: the desktop broadcasts once per second and the phone connects as soon as it receives a broadcast.

## 4. Design

| Stage | Approach | Notes |
|---|---|---|
| Capture | NAudio 3.1 `WasapiRecorder` + `WithLoopbackCapture()` | event-driven shared mode, 20 ms buffer, yields `float32/48 kHz/2ch` directly; automatically resamples when the format differs |
| Encoding | Concentus (**fully managed Opus**) | 48 kHz stereo, `OPUS_APPLICATION_AUDIO`, Complexity 10, VBR, in-band FEC; ≈0.2–0.4 ms per frame |
| Transport | self-designed UDP protocol (LAS1) | no head-of-line blocking, no retransmission; one frame per packet (10 ms → 100 packets/s); an optional **previous-frame resend in the same packet** gives one level of loss recovery at zero cost |
| Clock sync | NTP-style four timestamps (`SYNC_REQ/SYNC_RSP`) | clock offset estimated from the minimum-RTT sample, so the phone can show the **true end-to-end latency** (phone-side display only) |
| Jitter buffer | adaptive, ordered playback queue | low-latency 20–180 ms / audio-quality 60–400 ms, grows on underrun, shrinks slowly on a clean link, and trims the oldest frames when deeper than target; known losses are concealed with Opus PLC; sender idle time is filled with silence that is not counted as underrun |
| Discovery & keep-alive | UDP 47654 broadcast + unicast replies; phone sends `RR` every 100 ms | the phone sends `PROBE`, the desktop replies with `ANNOUNCE` (name, port, streaming state); when no keep-alive arrives within the disconnect timeout (default 8 s, configurable 1–600 s) the desktop **only pauses sending and keeps the session**, resuming immediately once feedback or a reconnect arrives |
| Remote settings | `SETTINGS` (type=11, TLV) | submitted only when Save settings is clicked → the receiver validates and applies them → it re-sends `HELLO_ACK` and pushes the authoritative state back (also once when the session is established) |
| Output | `AudioTrack` MODE_STREAM | default media path with ~80 ms buffer (the low-latency path was dropped to keep system audio effects) |

Protocol details: **[docs/PROTOCOL.md](docs/PROTOCOL.md)** (Chinese).

## 5. Verification (measured on the development machine)

### 5.1 Does capture still work after muting the local speakers?

On the target machine (Windows 11 + Senary Audio as the default playback device): a 440 Hz sine was played through `WasapiOut` and captured with `WasapiLoopbackCapture` while measuring RMS, toggling system mute in the middle —
RMS **unmuted 0.106082 → muted 0.106054 → unmuted again 0.106069**. Conclusion: **endpoint mute does not affect loopback capture**, so "mute the PC and keep streaming" works natively, without a virtual audio device.

### 5.2 End-to-end link measurement (local loopback, released build)

Capture `Senary Audio` / `48000 Hz / 2 ch / 32 bit Extensible(float)` / 20 ms buffer; play a 1000 Hz tone → capture → Opus 128 kbps/10 ms → UDP → decode to WAV on the receiving side:

- Decoded audio analysis: **RMS = 0.2017, 1000 Hz amplitude = 0.2836 (source amplitude 0.3) → "target frequency detected ✓"**
- End-to-end latency (capture instant → decode instant, including capture buffer and encoding): **19–20 ms**; network jitter **0.09–0.16 ms**; packet loss **0.00%**; encoding 0.2–0.4 ms/frame; the C# receiver and **Java (the very same code that ships inside the APK)** produced **identical** results (frame count, RMS and spectrum amplitude match bit for bit)

### 5.3 Verification status

| Item | Result |
|---|---|
| Desktop build / UI self-check | ✅ GUI creates normally, headless mode works; `--dumpui` reports **OVERLAPS=0 CLIPPED=0** at 200% scaling (2880×1800) |
| Capture chain & audio correctness / clean-link latency | ✅ measured: 19–20 ms, jitter 0.1–0.12 ms, 0% loss; 1 kHz source amplitude 0.3 → decoded 0.2836; end-to-end ≈100–180 ms (including ~80 ms audio output buffer), 0 underruns |
| Android core code (same sources on a JVM) | ✅ full discovery/session/jitter-buffer/Opus-decode chain runs; results **bit-identical** to the C# side (`scripts/run_core_probe.ps1`) |
| Android build & signing | ✅ signed, bundles the Concentus decoder (998 classes), no native libraries (installable on any ABI); manifest-class ↔ dex validation includes a **negative test** |
| Audio continuity (100 ms / 700 ms network stalls injected) | ✅ adaptive buffer converges within 5 s: 20 s run had only 3 single-frame concealments (≈0.15%), 0% loss; silence share over a 50 ms window **0.00%** |
| **Lossless PCM mode (including the 10 ms default frame size that used to crash)** | ✅ measured: `format=2/48000Hz/10ms/2ch`, 1570 kbps, latency 89–95 ms, 0% loss, 0 PLC, 0 underruns; per-second decoded RMS on the phone stays constant |
| Remote-settings round trip / two-end consistency | ✅ pushed `192kbps/20ms/mono/redundancy off/DTX on` → desktop log "applied" → phone received a new `HELLO_ACK` and the authoritative state; the manual-save path behaves the same, covering 11 desktop items + 3 phone-local items |
| Sample-rate switching / probing stops after connect / crash traces | ✅ `24 kHz mono 20 ms` passes; in a 16 s session the desktop only received 3 probe packets in the first 1.5 s and none afterwards; uncaught exceptions are written to `Android/data/com.lasaudio/files/crash-*.txt` |
| Phone UI / foreground service on a real device | ⚠️ no Android device or emulator was available in this environment, so on-device runtime verification was not performed |

## 6. Building

```powershell
powershell -ExecutionPolicy Bypass -File scripts\setup_toolchain.ps1   # 1) toolchain (JDK 17 + Android SDK + Gradle, China mirrors included)
powershell -ExecutionPolicy Bypass -File scripts\build_desktop.ps1     # 2) desktop (self-contained single-file exe + diagnostics tool)
powershell -ExecutionPolicy Bypass -File scripts\build_android.ps1     # 3) Android APK (signed release)
powershell -ExecutionPolicy Bypass -File scripts\run_conformance.ps1   # 4) end-to-end self-test (capture → encode → transport → decode → spectral check)
```
See **[docs/BUILD.md](docs/BUILD.md)** (Chinese) for the parameters and prerequisites of each script.

### 6.1 Automated build & release (GitHub Actions)

The repository ships **[.github/workflows/build.yml](.github/workflows/build.yml)**:

- Pushing to `main` or opening a PR → builds the desktop app and the APK on GitHub runners (to prove the code still builds)
- Pushing a tag such as `v1.0.1` → additionally uploads the artifacts to the matching Release and generates `SHA256SUMS.txt`

```powershell
git tag v1.0.1
git push origin main --tags
```

- The build number and file names come from the tag (`-Version` / `-PlasVersion`), and the APK's internal `versionName`/`versionCode` follow it as well
- Signing the APK requires three repository secrets (**Settings → Secrets and variables → Actions**): `LAS_KEYSTORE_BASE64` (base64 of the keystore file), `LAS_KEYSTORE_PASSWORD` and `LAS_KEYSTORE_ALIAS`. **Without them the workflow still produces an unsigned APK**, for testing only
- Actions is **free** for public repositories

## 7. Release artifacts

The binaries are large and therefore **not committed to Git**; they are published with each version on **[Releases](https://github.com/XiaoliuNya/LanAudioStream/releases/latest)**. `dist/SHA256SUMS.txt` is committed so you can verify your downloads.

| File | Size | SHA256 |
|---|---|---|
| `LanAudioStream-1.0.0-win-x64.exe` | 47.6 MB | `E7B7E9D2B769C62A5930ED7768142DAE87D04554A2E6E281D43ABE17BFCBB9EA` |
| `LanAudioStream-1.0.0.apk` |  0.8 MB | `15C8BB4EE3717F0609D4AD89BA5E3F26846F8F2CB6B4188C0C9C0BF8E4266FFA` |
| `LanAudioStream-1.0.0-source.zip` | ≈0.2 MB | see `dist/SHA256SUMS.txt` (the archive contains this README, so editing it changes its own hash) |
| `LoopbackClient-1.0.0-win-x64.exe` | 50.0 MB | see `dist/SHA256SUMS.txt` |

The desktop exe is a **.NET 10 self-contained single file** — double-click and run. The APK is a release build (`minSdk 26 / targetSdk 35`) signed with `keystore/lan-audio.jks` and verified by `apksigner verify`. The source archive contains `desktop/ + android/ + docs/ + scripts/ + README + LICENSE` (packaged from Git-tracked content only, i.e. without build outputs or the signing key).

## 8. Known limitations

- Desktop and phone must be on the **same broadcast-capable LAN** (enterprise/guest WiFi often isolates clients; use a phone hotspot or connect manually instead).
- Bluetooth headsets and speakers add 100–200 ms of their own latency — not introduced by this tool; use wired headphones when evaluating low-latency behaviour.
- After switching the default playback device on the desktop you must click "Start streaming" again (device-change notifications are not subscribed to yet).
- Single client: only one phone can receive at a time (the protocol could be extended to multicast; not implemented).
- The phone UI has no tablet/landscape layout (portrait-locked).

## 9. Logs and troubleshooting

The developer file-logging mode was removed; both ends use in-app log areas instead — the desktop shows a log box at the bottom of the main window, the phone has a "Logs" section at the bottom of its page. They cover handshakes/disconnects, paused sending and resumption, settings saves, format and state changes, exception stack traces, and so on. Uncaught exceptions on the phone are still written to `Android/data/com.lasaudio/files/crash-*.txt`. Detailed steps: **[docs/USER_GUIDE.md](docs/USER_GUIDE.md)** (Chinese).

## 10. About AI involvement

**All of the code, tests, documentation and build scripts in this project were generated by an AI coding agent (DSH, DeepSeek Harness).** The human participant's role is limited to:

1. stating requirements and acceptance criteria
2. finding bugs and usability problems during real use
3. handing those problems back to the AI to fix, then re-verifying on a real device

Please keep this in mind:

- Commits carry the trailer `AI-Assisted-by: DSH (DeepSeek Harness)` (added automatically by a repository hook, see below). `Co-authored-by:` is deliberately avoided, because GitHub resolves the email inside that trailer to a real account and would misattribute the work to a same-named user
- Every "measured" figure quoted here was produced by the AI on the development machine and was **not** independently recomputed by a human — treat them as **developer self-reported results**, not as a third-party test report
- AI-generated code may contain unknown defects or security gaps; evaluate it yourself before using it in production (see also §8)

### Enabling the commit-attribution hook (run once after cloning)

```powershell
git config core.hooksPath .githooks
```

Every subsequent `git commit` then appends the AI attribution automatically. On Linux/macOS, run `chmod +x .githooks/prepare-commit-msg` if Git reports that the hook is not executable. If a particular commit has nothing to do with the AI, skip it with `git commit --no-verify`.

## 11. License

This project is released under the [MIT License](LICENSE).

> The complete record of each iteration and defect fix is in **[docs/CHANGELOG.md](docs/CHANGELOG.md)** (Chinese).
