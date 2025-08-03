# 🗣️ RuneLite TTS Dialogue Plugin

Bring your RuneScape adventures to **life** with full voice acting!  
This plugin reads **in-game dialogue out loud** using different AI voices for NPCs and the player character. Experience immersive conversations with unique voices for every race and gender! 🎧🧙‍♂️

> Powered by 🧠 [Piper](https://github.com/rhasspy/piper) + 🎮 RuneLite

---

## ✨ Features

- 🔊 **Text-to-Speech for all dialogue** (NPC & Player)
- 🎭 **16-Voice Matrix System** - 8 races × 2 genders each with unique voices:
  - 📱 **Player Voices**: Male & Female options
  - 👥 **Human Voices**: Most common NPCs (guards, merchants, etc.)
  - 🧝 **Elf Voices**: Mystical male, ethereal female
  - ⛏️ **Dwarf Voices**: Gruff male, sturdy female
  - 👺 **Goblin Voices**: Raspy male, crude female
  - 🏔️ **Troll Voices**: Deep male, primitive female
  - 💀 **Undead Voices**: Hollow male, eerie female
  - 😈 **Demon Voices**: Sinister male, otherworldly female
- 🤖 **Automatic NPC Detection** - Intelligently detects race and gender from NPC names, IDs, and context
- 🧠 **Production-Ready** - Local, fast, offline-capable TTS (no cloud dependencies)
- ⏩ **Smart Playback** - Cancels audio on skipped dialogue
- 🏥 **Server Health Monitoring** - Automatic health checks with intelligent fallbacks
- 🔄 **Robust Fallback System** - Uses alternative voices when preferred servers are unavailable
- 🐛 **Debug Mode** - Detailed NPC detection logging for troubleshooting

---

## 🔧 Dev Setup

### 1. Clone the repo

```bash
git clone https://github.com/yourusername/runelite-tts-plugin.git
cd runelite-tts-plugin
```

### 2. Install & run TTS voice servers (automated)

This plugin uses 16 specialized TTS voice servers (8 races × 2 genders) running in Docker containers. We've included an automated setup script to handle everything!

#### 🚀 One-Command Setup

```bash
# Start all 16 voice servers automatically
./setup-voices.sh start
```

This will:
- 🐳 Pull the required Docker images
- 🎭 Start 16 voice containers with optimized voice models
- ⏳ Wait for all servers to initialize
- ✅ Verify all voices are working

#### 📊 Voice Server Matrix

The setup script creates the following voice servers:

| Race | Male Voice (Port) | Female Voice (Port) |
|------|------------------|--------------------|
| 👤 **Player** | `player-male` (59125) | `player-female` (59126) |
| 👥 **Human** | `human-male` (59127) | `human-female` (59128) |
| 🧝 **Elf** | `elf-male` (59129) | `elf-female` (59130) |
| ⛏️ **Dwarf** | `dwarf-male` (59131) | `dwarf-female` (59132) |
| 👺 **Goblin** | `goblin-male` (59133) | `goblin-female` (59134) |
| 🏔️ **Troll** | `troll-male` (59135) | `troll-female` (59136) |
| 💀 **Undead** | `undead-male` (59137) | `undead-female` (59138) |
| 😈 **Demon** | `demon-male` (59139) | `demon-female` (59140) |

#### 🛠️ Voice Server Management

```bash
# Check server status
./setup-voices.sh status

# Test all voice servers
./setup-voices.sh test

# Stop all servers
./setup-voices.sh stop

# Restart everything
./setup-voices.sh restart
```

#### 🎭 Voice Model Selection

Each voice uses carefully selected Piper models from [HuggingFace](https://huggingface.co/rhasspy/piper-voices) optimized for their character:

- **Player Voices**: Clear, neutral tones
- **Human Voices**: Natural British/American accents
- **Elf Voices**: Elegant, mystical quality
- **Dwarf Voices**: Gruff, sturdy characteristics
- **Goblin Voices**: Raspy, crude tones
- **Troll Voices**: Deep, primitive sounds
- **Undead Voices**: Hollow, eerie quality
- **Demon Voices**: Sinister, otherworldly tones

> **⚡ Performance Tip:** The script automatically downloads and caches voice models. First startup takes ~5-10 minutes, subsequent starts are much faster!

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

## 🚑 Troubleshooting

### 🔍 Server Health Checking

The plugin automatically monitors TTS server health and provides fallback options:

#### Check Server Status
```bash
# Use the included script
./setup-voices.sh status

# Or manually check each port
curl http://localhost:59125  # Default NPC voice
curl http://localhost:59126  # Player voice
curl http://localhost:59127  # Dwarf voice
# ... etc
```

#### Enable Health Status Logging
In the plugin configuration:
1. Enable **"Show Server Status"** 
2. Restart the plugin
3. Check RuneLite logs for server health information

#### Common Issues

**⚠️ No TTS servers running:**
- **Solution:** Run `./setup-voices.sh start`
- **Check:** Docker containers are running with `docker ps`

**⚠️ Some voices unavailable:**
- **Fallback enabled:** Plugin will use alternative voices automatically
- **Fallback disabled:** Enable "Voice Fallbacks" in configuration
- **Manual fix:** Restart specific containers: `docker restart tts-dwarf`

**⚠️ TTS requests timing out:**
- **Check:** Server health with `./setup-voices.sh test`
- **Solution:** Increase timeout or restart containers
- **Debug:** Check container logs: `docker logs tts-player`

**🎭 Wrong voices (males speaking as females):**
- **Cause:** Voice model mismatch or wrong port assignment
- **Check:** Verify voice models are correctly downloaded in containers
- **Solution:** Restart specific voice containers: `docker restart tts-elf`
- **Debug:** Check which models are actually loaded: `docker logs tts-elf`

**🔊 No audio output:**
- **Check:** System audio is working and not muted
- **Verify:** TTS files are being generated in `/tmp/`
- **Test:** Manual voice test: `curl -X POST -H "Content-Type: text/plain" -d "test" http://localhost:59126 -o test.wav && afplay test.wav`

#### Plugin Configuration Options

- **Enable Race-Based Voices** - Turn on/off the race detection system
- **Show Server Status** - Display server health in logs on startup
- **Enable Voice Fallbacks** - Use alternative voices when preferred ones are down
- **Per-race voice selection** - Choose specific voices for each race

#### Voice Server Management

```bash
# Quick commands
./setup-voices.sh start    # Start all voice servers
./setup-voices.sh stop     # Stop all voice servers  
./setup-voices.sh status   # Check server health
./setup-voices.sh test     # Test all servers
./setup-voices.sh restart  # Restart everything
```

### 📝 Logs to Check

**Plugin logs show:**
- Server health status on startup
- Voice fallback usage
- NPC race detection results
- TTS request failures

**Docker logs show:**
- Model download progress
- Server startup issues
- TTS processing errors

---

## 🧠 Tech Stack

- Java 🥃
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