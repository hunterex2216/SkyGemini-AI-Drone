# 🛸 SkyGemini AI Drone - Native Android AI Copilot

[![Android 7.0+](https://img.shields.io/badge/Android-7.0%2B-green.svg)](https://developer.android.com)
[![Google Gemini Live API](https://img.shields.io/badge/Gemini%20Live%20API-gemini--3.8--live-blue.svg)](https://ai.google.dev)
[![Architecture](https://img.shields.io/badge/Architecture-Dual--Network%20(WiFi%20%2B%20Cellular)-orange.svg)]()
[![Voice](https://img.shields.io/badge/Language-Bengali%20%26%20English-purple.svg)]()

**SkyGemini AI Drone** is a native Android AI Copilot application that connects directly to **Google Gemini Live API** (`gemini-3.8-live`) to fly Wi-Fi FPV drones using autonomous Bengali and English voice commands, with real-time 0ms low-latency HD camera streaming and a Cyberpunk cockpit HUD overlay.

---

## ✨ Features

- 🗣️ **Pure Google Gemini Live AI Voice Copilot**:
  - Direct connection to `wss://generativelanguage.googleapis.com/.../BidiGenerateContent`.
  - Zero robotic Android TTS — uses real-time 24kHz PCM Gemini Live voice (`Puck`).
  - 16kHz microphone audio streaming with hardware acoustic echo cancellation and noise suppression.
  - Understands natural spoken Bengali (বাংলা) and English commands autonomously.

- 🛜 **Dual-Network Architecture (Android Wi-Fi + 4G/5G Cellular)**:
  - Solves Android's Wi-Fi routing conflict: Drone Wi-Fi lacks internet, so Gemini Live routes over Cellular (4G/5G) via `bindProcessToNetwork`.
  - Drone UDP sockets (`192.168.4.153:8080` & `8090`) are explicitly bound to `TRANSPORT_WIFI`.
  - Zero `EPERM` socket permission errors on modern Android (10+).

- 📷 **Ultra-Fast 0ms Latency HD Camera Feed**:
  - High-priority UDP port 8080 receiver matching reverse-engineered `libCamera.so` (`Image::put`).
  - Preallocated 400KB buffer with 1464-byte slice indexed reassembly.
  - Non-blocking `RGB_565` hardware-accelerated decoding.
  - Streams live camera frames into Gemini Multimodal Vision for live scene understanding.

- 🎮 **Tactile Dual Flight Controls**:
  - Virtual dual joysticks (Left: Throttle/Yaw, Right: Pitch/Roll).
  - Tactical Cyberpunk HUD with pitch ladder, artificial horizon, roll indicator, and live telemetry.
  - Hardware buttons: `ARM`, `TAKEOFF`, `LAND`, `360° FLIP`, `CALIBRATE`, `EMERGENCY STOP`.

---

## 🎙️ Bengali Voice Commands (বাংলা ভয়েস কমান্ড)

| Voice Command (বাংলা) | Action | Tool Called |
|-----------------------|--------|-------------|
| "ড্রোন উড়াও" / "টেক অফ" / "স্টার্ট" / "উড়তে দাও" | Launch and hover drone | `takeoff()` |
| "নামাও" / "ল্যান্ড করো" / "নিচে আসো" | Gently land drone | `land()` |
| "মোটর চালু করো" / "আর্ম করো" | Arm motors | `arm_motors()` |
| "ডিগবাজি দাও" / "ফ্লিপ করো" | 360° Aerial Stunt Flip | `flip_360()` |
| "থামো" / "জরুরি ব্রেক" / "বন্ধ করো" | Cut motors immediately | `emergency_stop()` |
| "সামনে যাও" / "পেছনে আসো" | Fly forward / backward | `fly_forward()` / `fly_backward()` |
| "বামে ঘুরাও" / "ডানে ঘুরাও" | Rotate drone left / right | `turn_left()` / `turn_right()` |
| "উপরে তোলো" / "নিচে নামাও" | Ascend / Descend | `ascend()` / `descend()` |
| "সামনে কি দেখতে পাচ্ছ?" | Multimodal Vision description | Gemini Vision Frame Analysis |

---

## 🛠️ Project Structure

```
SkyGeminiApp/
├── AndroidManifest.xml              # Dual-network, audio, and camera permissions
├── build.sh                         # 1-command build script for Termux & Linux
├── .gitignore                       # Clean repository configuration
├── res/                             # HUD layouts, styles, strings, colors
│   ├── layout/activity_main.xml
│   └── values/{colors,strings,styles}.xml
└── src/com/skygemini/drone/
    ├── MainActivity.java            # Main activity, HUD & button bindings
    ├── GeminiLiveClient.java        # WebSocket Live API client (gemini-3.8-live)
    ├── DualNetworkManager.java      # Dual Wi-Fi + Cellular network routing
    ├── VideoReceiver.java           # Ultra-fast 0ms latency UDP video receiver
    ├── DroneController.java         # 25Hz command transmission loop
    ├── VirtualJoystickView.java     # Dual floating joystick controller
    └── HudOverlayView.java          # Cyberpunk cockpit telemetry HUD
```

---

## 🔨 How to Build on Termux / Linux

1. Ensure the required tools are installed:
   ```bash
   pkg install openjdk-17 aapt apksigner
   ```

2. Clone this repository:
   ```bash
   git clone https://github.com/hunterex2216/SkyGemini-AI-Drone.git
   cd SkyGemini-AI-Drone
   ```

3. Run the automated build script:
   ```bash
   ./build.sh
   ```

4. Install the generated APK:
   ```bash
   termux-open /data/user/0/com.termux/files/home/SkyGemini-AI-Drone.apk
   ```

---

## 🔑 Configuration

1. Launch the app.
2. Tap the **⚙️ Settings** icon in the top right corner.
3. Enter your Google Gemini API Key from [Google AI Studio](https://aistudio.google.com).
4. Connect your phone's Wi-Fi to your Drone's Wi-Fi network (`192.168.4.153`).
5. Ensure Mobile Data (4G/5G) is active.
6. Tap **🎙️ START GEMINI LIVE** — Gemini will speak out loud in Bengali to welcome you, and you're ready to fly!

---

## 📄 License

MIT License. Designed and engineered for autonomous drone piloting with Google Gemini Live API.
