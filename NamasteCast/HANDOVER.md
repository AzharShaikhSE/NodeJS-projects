# Handover: install and test NamasteCast on the real Fire TV Stick

For Claude Code running on Azhar's Mac, on the same Wi-Fi as the Fire TV Stick. Everything below the line is written to you, the agent.

**How Azhar starts it:**
```bash
git clone https://github.com/AzharShaikhSE/NodeJS-projects.git && cd NodeJS-projects
git checkout claude/zealous-tesla-gvq2j4
claude "Follow NamasteCast/HANDOVER.md"
```

---

## Context

NamasteCast lets Azhar watch NamasteDev courses on an LG TV through a Fire TV Stick. AirPlay mirroring gave audio with no picture, almost certainly because the lessons use DRM, which blocks mirroring. So the app has two modes:

1. **Play on TV:** a Fire TV app (Kotlin WebView with Widevine allowed) opens the lesson page itself, logged into Azhar's own account. The phone or laptop is a remote, served by the TV app at `http://<tv-ip>:8765`.
2. **Mirror my laptop:** WebRTC screen/tab mirroring for non-DRM content. `NamasteCast/laptop-mirror` runs a localhost page plus a signaling relay.

Read `NamasteCast/README.md` first. All unit, browser and CI tests pass. **Nothing has run on real Fire TV hardware yet.** That's your job.

## Rules

- Never ask for, type, or store Azhar's NamasteDev password. He logs in on the TV himself, typing with the phone remote's "Type on TV".
- Don't try to bypass DRM, extract video URLs, or record lessons. If DRM blocks playback, report it and stop that line of work.
- Copy-protected video shows **black in `adb screencap` even when it plays fine on the TV.** Never judge DRM playback from a screenshot. Ask Azhar what he sees on the TV.
- Commit fixes to branch `claude/zealous-tesla-gvq2j4`, keep tests green, and rebuild `NamasteCast/dist/NamasteCast.apk` if Kotlin or assets change.

## Steps

### 0. Ask Azhar to do these on the TV (you can't)
- Settings > My Fire TV > Developer options: **ADB debugging ON**. If Developer options is hidden: Settings > My Fire TV > About, click the device name 7 times.
- Tell you the TV's IP: Settings > My Fire TV > About > Network.
- Tell you the model: Settings > My Fire TV > About (for example "Fire TV Stick 4K Max (2nd Gen)").

### 1. Tools on the Mac
```bash
brew install android-platform-tools     # adb
node -v                                 # needs 18+
```
To rebuild the APK you also need JDK 17 and the Android SDK (platform 34): `brew install --cask temurin@17 android-commandlinetools`. Skip this unless you change Kotlin code.

### 2. Connect and install
```bash
adb connect <TV_IP>:5555          # TV shows "Allow debugging?". Azhar presses Allow (tick "always")
adb devices                       # should list <TV_IP>:5555  device
adb install -r NamasteCast/dist/NamasteCast.apk
adb shell am start -n com.namastecast.tv/.MainActivity
adb shell getprop ro.product.model; adb shell getprop ro.build.version.sdk
adb shell dumpsys package com.google.android.webview | grep versionName   # may be com.amazon.webview.chromium instead
```

### 3. Smoke test the TV's API from the Mac
```bash
curl -s http://<TV_IP>:8765/api/ping          # {"ok":true,"name":...,"app":"NamasteCast"}
adb exec-out screencap -p > tv.png            # should show the QR / PIN card
```
Read the PIN from the screenshot, or ask Azhar. Then:
```bash
curl -s -H "X-Pin: <PIN>" http://<TV_IP>:8765/api/state | head -c 400
curl -s -H "X-Pin: <PIN>" -H 'Content-Type: application/json' \
  -d '{"type":"open","url":"https://www.youtube.com"}' http://<TV_IP>:8765/api/cmd
```
Live logs: `adb logcat -s NamasteCast chromium` (in a second terminal).

### 4. Checks, in order. Record each result.

| # | Check | How | Pass if |
|---|---|---|---|
| 1 | App launches, card shows QR + IP + PIN | screencap | card visible |
| 2 | Phone remote pairs | Azhar scans QR | green dot, TV name shows |
| 3 | Pointer + click with Fire remote and phone touchpad | Azhar | pointer moves, links open |
| 4 | Non-DRM video plays and remote controls it | open a YouTube video via `/api/cmd`, then `{"type":"video","action":"toggle"}` | plays and pauses; `/api/state` shows `video.hasVideo` |
| 5 | **NamasteDev login** | Azhar logs in using touchpad + "Type on TV" | logged in, survives an app restart |
| 6 | **NamasteDev lesson plays with picture** | Azhar opens a lesson; **ask him** | picture + sound on the TV |
| 7 | If 6 is black: try mobile site | `{"type":"mode","desktop":false}` then reopen the lesson; also check `adb logcat` for `EME`/`Widevine`/`MediaDrm` errors | picture appears |
| 8 | TV mode / play-pause / ±10s on a lesson | phone remote | works, or note which fall back to taps |
| 9 | Page-load speed | time from "Play" to lesson visible | note seconds; compare desktop vs mobile site |
| 10 | **Laptop mirroring** | `cd NamasteCast/laptop-mirror && npm start`, Find my TV, PIN, Smooth, share a tab with a YouTube video | picture + sound on TV; note the stats line (fps, Mbps, codec) |
| 11 | Mirror quality presets | try Sharp and Motion | note fps and whether it stutters |
| 12 | Mirror stop paths | page Stop, Chrome "Stop sharing", Fire remote Back | TV returns to the previous page each time |

### 5. Likely problems and where to look

- **WebRTC connects on the laptop but the TV stays on "Connecting...":** ICE failure. Check that the relay unmasked `.local` candidates (`laptop-mirror/server.js` `unmaskSdp`). Log both SDPs. Mac firewall: allow incoming for Chrome/Node.
- **Mirror is choppy on an older Stick:** check the codec in the stats line. If not H264, the Stick may lack hardware WebRTC H.264 in its WebView. Use Smooth (720p) and drop the bitrate in `sender.html` `QUALITY`.
- **Black DRM lesson in WebView (check 6/7 fail):** WebView may lack the Widevine level the site needs. Report it. The fallback is the Silk browser or an HDMI cable. Don't try workarounds.
- **Slow pages (check 9):** if the Stick is old, consider these and measure before/after:
  1. default `desktop` to `false` (`MainActivity.isDesktop()`),
  2. state poll 1s → 2s and skip it while `customView != null` (`stateLoop`),
  3. `web.settings.cacheMode = WebSettings.LOAD_DEFAULT` is already the default, so don't bother.

### 6. Finish

Write `NamasteCast/DEVICE-TEST.md` with: Fire TV model, Fire OS/SDK level, WebView version, and a pass/fail table for checks 1-12 with notes and numbers. Commit it plus any fixes to `claude/zealous-tesla-gvq2j4` and push. Tell Azhar in 5 lines or fewer: what works, what doesn't, what you changed, what he should do next.
