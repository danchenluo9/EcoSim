#!/bin/bash
# EcoSim launcher — starts the Java backend and Vite frontend together.
# Usage:
#   ./start.sh      — playback mode (no API calls, uses mock-data/)
#   ./start.sh 1    — record mode (calls real API and saves responses to mock-data/)

set -e
cd "$(dirname "$0")"

# Export variables from .env if present (KEY=VALUE lines).
if [ -f .env ]; then
    set -a
    . ./.env
    set +a
fi

# LLM mode: record when called with argument 1, otherwise playback.
if [ "${1}" = "1" ]; then
    export ECOSIM_LLM_MODE=record
    echo "▶ LLM mode: record (responses will be saved to mock-data/)"
else
    export ECOSIM_LLM_MODE=playback
    echo "▶ LLM mode: playback (using saved responses from mock-data/)"
fi

cleanup() {
    echo ""
    echo "Stopping EcoSim..."
    kill "$BACKEND_PID" "$FRONTEND_PID" 2>/dev/null || true
    wait "$BACKEND_PID" "$FRONTEND_PID" 2>/dev/null || true
    echo "Done."
    exit 0
}
trap cleanup INT TERM

echo "▶ Starting backend (Maven)..."
mvn -q exec:java &
BACKEND_PID=$!

echo "▶ Starting frontend (Vite)..."
cd frontend && npm i && npm run dev &
FRONTEND_PID=$!
cd ..

echo "▶ Waiting for servers to start..."
sleep 5

echo "▶ Opening browser..."
URL="http://localhost:5173"
if command -v open &>/dev/null; then
    open "$URL"          # macOS
elif command -v xdg-open &>/dev/null; then
    xdg-open "$URL"      # Linux
elif command -v start &>/dev/null; then
    start "$URL"         # Windows Git Bash
fi

echo ""
echo "EcoSim is running at $URL"
echo "Press Ctrl+C to stop both servers."
echo ""

wait "$BACKEND_PID"
