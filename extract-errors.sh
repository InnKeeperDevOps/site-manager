#!/bin/bash
# Monitors app.log and extracts ERROR lines into error.log
# Runs as a background tail process

LOG_FILE="/home/claude/site-manager/app.log"
ERROR_LOG="/home/claude/site-manager/error.log"

# Process existing errors first
if [ -f "$LOG_FILE" ]; then
    grep -i '\[ERROR\]\|ERROR\|Exception\|FATAL\|BUILD FAILED' "$LOG_FILE" > "$ERROR_LOG" 2>/dev/null
fi

# Then follow new lines
tail -n 0 -F "$LOG_FILE" 2>/dev/null | while read -r line; do
    if echo "$line" | grep -qiE '\[ERROR\]|ERROR|Exception|FATAL|BUILD FAILED'; then
        echo "$line" >> "$ERROR_LOG"
    fi
done
