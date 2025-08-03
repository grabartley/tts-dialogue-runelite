#!/bin/bash

# TTS Dialogue Plugin - Voice Server Setup Script
# This script sets up multiple Piper TTS voice servers for different NPC races

echo "🗣️  Setting up TTS Voice Servers for RuneLite TTS Dialogue Plugin"
echo "================================================================="

# Voice configuration arrays (compatible with all shells)
# 16-voice matrix: 8 races × 2 genders + 2 player voices
VOICE_NAMES=(
    "player-male" "player-female" 
    "human-male" "human-female" 
    "elf-male" "elf-female" 
    "dwarf-male" "dwarf-female" 
    "goblin-male" "goblin-female" 
    "troll-male" "troll-female" 
    "undead-male" "undead-female" 
    "demon-male" "demon-female"
)
VOICE_PORTS=(
    "59125" "59126" 
    "59127" "59128" 
    "59129" "59130" 
    "59131" "59132" 
    "59133" "59134" 
    "59135" "59136" 
    "59137" "59138" 
    "59139" "59140"
)
VOICE_DESCRIPTIONS=(
    "Player Male" "Player Female" 
    "Human Male" "Human Female" 
    "Elf Male (Mystical)" "Elf Female (Ethereal)" 
    "Dwarf Male (Gruff)" "Dwarf Female (Sturdy)" 
    "Goblin Male (Raspy)" "Goblin Female (Crude)" 
    "Troll Male (Deep)" "Troll Female (Primitive)" 
    "Undead Male (Hollow)" "Undead Female (Eerie)" 
    "Demon Male (Sinister)" "Demon Female (Otherworldly)"
)
VOICE_URLS=(
    # Player voices
    "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_GB/northern_english_male/medium/en_GB-northern_english_male-medium.onnx?download=true"
    "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/amy/medium/en_US-amy-medium.onnx?download=true"
    
    # Human voices (most common NPCs)
    "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_GB/vctk/medium/en_GB-vctk-medium.onnx?download=true"
    "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/kathleen/low/en_US-kathleen-low.onnx?download=true"
    
    # Elf voices (elegant, ethereal)
    "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_GB/alba/medium/en_GB-alba-medium.onnx?download=true"
    "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/lessac/medium/en_US-lessac-medium.onnx?download=true"
    
    # Dwarf voices (gruff, sturdy)
    "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_GB/northern_english_male/medium/en_GB-northern_english_male-medium.onnx?download=true"
    "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/joe/medium/en_US-joe-medium.onnx?download=true"
    
    # Goblin voices (raspy, crude)
    "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/danny/low/en_US-danny-low.onnx?download=true"
    "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/bryce/medium/en_US-bryce-medium.onnx?download=true"
    
    # Troll voices (deep, primitive)
    "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/ryan/high/en_US-ryan-high.onnx?download=true"
    "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/ryan/medium/en_US-ryan-medium.onnx?download=true"
    
    # Undead voices (hollow, eerie)
    "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/ljspeech/high/en_US-ljspeech-high.onnx?download=true"
    "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/ljspeech/medium/en_US-ljspeech-medium.onnx?download=true"
    
    # Demon voices (sinister, otherworldly)
    "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/john/medium/en_US-john-medium.onnx?download=true"
    "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/libritts/high/en_US-libritts-high.onnx?download=true"
)

# Function to check if Docker is installed
check_docker() {
    if ! command -v docker &> /dev/null; then
        echo "❌ Docker is not installed. Please install Docker first."
        echo "   Visit: https://docs.docker.com/get-docker/"
        exit 1
    fi
}

# Function to stop and remove existing containers
cleanup_containers() {
    echo "🧹 Cleaning up existing voice containers..."
    for i in "${!VOICE_NAMES[@]}"; do
        local voice_type="${VOICE_NAMES[$i]}"
        local container_name="tts-$voice_type"
        docker stop "$container_name" 2>/dev/null && echo "  Stopped $container_name"
        docker rm "$container_name" 2>/dev/null && echo "  Removed $container_name"
    done
}

# Function to start a voice container
start_voice_container() {
    local voice_type=$1
    local port=$2
    local url=$3
    local container_name="tts-$voice_type"
    
    echo "🎭 Starting $voice_type voice server on port $port..."
    
    docker run -d \
        -p "$port:5000" \
        -e MODEL_DOWNLOAD_LINK="$url" \
        --name "$container_name" \
        --restart unless-stopped \
        artibex/piper-http
    
    if [ $? -eq 0 ]; then
        echo "  ✅ $voice_type voice server started successfully"
    else
        echo "  ❌ Failed to start $voice_type voice server"
    fi
}

# Function to wait for containers to be ready
wait_for_voices() {
    echo "⏳ Waiting for voice servers to initialize..."
    echo "   (This may take a few minutes as models are downloaded)"
    
    local max_attempts=60
    local ready_count=0
    local total_voices=${#VOICE_NAMES[@]}
    
    for attempt in $(seq 1 $max_attempts); do
        ready_count=0
        
        for i in "${!VOICE_NAMES[@]}"; do
            local port="${VOICE_PORTS[$i]}"
            
            if curl -s -X POST -H "Content-Type: text/plain" -d "test" "http://localhost:$port" > /dev/null 2>&1; then
                ((ready_count++))
            fi
        done
        
        echo "   $ready_count/$total_voices voice servers ready..."
        
        if [ $ready_count -eq $total_voices ]; then
            echo "✅ All voice servers are ready!"
            return 0
        fi
        
        sleep 5
    done
    
    echo "⚠️  Some voice servers may still be initializing. Check individual containers:"
    echo "   docker logs tts-<voice-type>"
}

# Function to display status
show_status() {
    echo ""
    echo "📊 Voice Server Status:"
    echo "======================"
    
    for i in "${!VOICE_NAMES[@]}"; do
        local voice_type="${VOICE_NAMES[$i]}"
        local port="${VOICE_PORTS[$i]}"
        local description="${VOICE_DESCRIPTIONS[$i]}"
        local container_name="tts-$voice_type"
        
        if docker ps | grep -q "$container_name"; then
            local status="🟢 Running"
            if curl -s -X POST -H "Content-Type: text/plain" -d "test" "http://localhost:$port" > /dev/null 2>&1; then
                status="🟢 Ready"
            else
                status="🟡 Starting"
            fi
        else
            status="🔴 Stopped"
        fi
        
        printf "  %-15s (port %s) - %-25s: %s\n" "$voice_type" "$port" "$description" "$status"
    done
    
    echo ""
    echo "🎭 Voice Matrix Overview:"
    echo "========================"
    echo "  📱 Player Voices: player-male, player-female"
    echo "  👥 Human Voices: human-male, human-female (most common NPCs)"
    echo "  🧝 Elf Voices: elf-male (mystical), elf-female (ethereal)"
    echo "  ⛏️  Dwarf Voices: dwarf-male (gruff), dwarf-female (sturdy)"
    echo "  👺 Goblin Voices: goblin-male (raspy), goblin-female (crude)"
    echo "  🏔️  Troll Voices: troll-male (deep), troll-female (primitive)"
    echo "  💀 Undead Voices: undead-male (hollow), undead-female (eerie)"
    echo "  😈 Demon Voices: demon-male (sinister), demon-female (otherworldly)"
}

# Function to test voices
test_voices() {
    echo ""
    echo "🧪 Testing voice servers..."
    echo "=========================="
    
    for i in "${!VOICE_NAMES[@]}"; do
        local voice_type="${VOICE_NAMES[$i]}"
        local port="${VOICE_PORTS[$i]}"
        
        echo "Testing $voice_type voice (port $port)..."
        if curl -s -X POST -H "Content-Type: text/plain" \
           -d "Hello from $voice_type voice!" \
           "http://localhost:$port" -o "/tmp/test_$voice_type.wav"; then
            echo "  ✅ $voice_type voice test successful"
            # Optionally play the audio file if you have a player installed
            # afplay "/tmp/test_$voice_type.wav" 2>/dev/null || true
        else
            echo "  ❌ $voice_type voice test failed"
        fi
    done
}

# Main execution
main() {
    case "${1:-start}" in
        "start")
            check_docker
            cleanup_containers
            
            echo "🚀 Starting voice servers..."
            for i in "${!VOICE_NAMES[@]}"; do
                local voice_type="${VOICE_NAMES[$i]}"
                local port="${VOICE_PORTS[$i]}"
                local url="${VOICE_URLS[$i]}"
                start_voice_container "$voice_type" "$port" "$url"
            done
            
            wait_for_voices
            show_status
            
            echo ""
            echo "🎉 Setup complete! You can now use the TTS Dialogue plugin."
            echo "💡 Tip: Run './setup-voices.sh status' to check server status"
            echo "🛑 To stop all servers: './setup-voices.sh stop'"
            ;;
            
        "stop")
            echo "🛑 Stopping all voice servers..."
            cleanup_containers
            echo "✅ All voice servers stopped"
            ;;
            
        "status")
            show_status
            ;;
            
        "test")
            test_voices
            ;;
            
        "restart")
            echo "🔄 Restarting voice servers..."
            cleanup_containers
            sleep 2
            main start
            ;;
            
        *)
            echo "Usage: $0 {start|stop|status|test|restart}"
            echo ""
            echo "Commands:"
            echo "  start   - Start all voice servers (default)"
            echo "  stop    - Stop all voice servers"
            echo "  status  - Show status of all voice servers"
            echo "  test    - Test all voice servers"
            echo "  restart - Restart all voice servers"
            exit 1
            ;;
    esac
}

main "$@"
