# 🗣️ RuneLite TTS Dialogue Plugin

Bring your RuneScape adventures to **life** with full voice acting!  
This plugin reads **in-game dialogue out loud** using different AI voices for NPCs and the player character. Whether you're deep in a quest or roasting goblins, you’ll *hear* it all 🎧🧙‍♂️

> Powered by 🧠 [Piper](https://github.com/rhasspy/piper) + 🎮 RuneLite

---

## ✨ Features

- 🔊 **Text-to-Speech for all dialogue** (NPC & Player)
- 🧍‍♂️ Different voices for **you** and **everyone else**
- 🧠 Local, fast, offline-capable TTS (no cloud nonsense)
- ⏩ Cancels playback on skipped dialogue

---

## 🔧 Dev Setup

### 1. Clone the repo

```bash
git clone https://github.com/yourusername/runelite-tts-plugin.git
cd runelite-tts-plugin
```

### 2. Install & run local TTS servers (via Docker)

This plugin talks to local TTS services running in Docker containers. Run these before launching RuneLite.

#### 🧍 Player voice (British 🇬🇧 male - VCTK)

```bash
docker run -it -p 59126:5000 \
  -e MODEL_DOWNLOAD_LINK="https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_GB/vctk/medium/en_GB-vctk-medium.onnx?download=true" \
  artibex/piper-http
```

#### 🧙 NPC voice (Northern 🇬🇧 male)

```bash
docker run -it -p 59125:5000 \
  -e MODEL_DOWNLOAD_LINK="https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_GB/northern_english_male/medium/en_GB-northern_english_male-medium.onnx?download=true" \
  artibex/piper-http
```

> These expose ports `59125` and `59126` to the plugin so it can send speech requests to the right voice.

---

## 🧪 Building & Running the Plugin

### Requirements

- ✅ Java 17
- 🛠️ Gradle (wrapper included)

### Build the plugin

```bash
./gradlew build
```

### Run in test client

To test the plugin in a standalone RuneLite client, run the `com.grahambartley.TTSDialoguePluginTest` class with the following VM options:

```text
-ea --add-exports=java.desktop/com.apple.eawt=ALL-UNNAMED
```

You can run it directly from your IDE (like IntelliJ) or configure it in `build.gradle` for CLI use.

Drop the built `.jar` into your RuneLite `plugins` folder or use RuneLite's External Plugin Manager if you know the vibes 🔌

---

## 🧠 Tech Stack

- Java 🧃
- Piper (TTS) 🎙️
- Docker 🐳
- RuneLite Plugin Framework 🧩

---

## 🎯 Future Ideas

- Custom voice models for specific NPCs 😈

---

## 🙌 Shoutout

Big love to [Rhasspy](https://github.com/rhasspy/piper) for Piper, and the RuneLite devs for making plugin dev actually fun.

---

## 📬 Contribute

Got ideas? Found a bug? Shout in the issues 💥

---

**Made with love in Gielinor** 💖