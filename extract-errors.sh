#!/bin/bash
# Monitors app.log and extracts ERROR lines into error.log
# Runs as a background tail process

# Ensure only one instance runs at a time
LOCK_FILE="/tmp/extract-errors.lock"
exec 200>"$LOCK_FILE"
if ! flock -n 200; then
    echo "Another extract-errors.sh instance is already running, exiting." >&2
    exit 0
fi

LOG_FILE="/home/claude/site-manager/app.log"
ERROR_LOG="/home/claude/site-manager/error.log"

# Pattern matches actual errors, not class names containing "Exception"
# Matches: log lines with ERROR/FATAL level, stack traces, BUILD FAILED
ERROR_PATTERN='\] (ERROR|FATAL) |^\tat |^Caused by:|BUILD FAILED'

# Process existing errors first
if [ -f "$LOG_FILE" ]; then
    grep -E "$ERROR_PATTERN" "$LOG_FILE" > "$ERROR_LOG" 2>/dev/null
fi

# Then follow new lines
tail -n 0 -F "$LOG_FILE" 2>/dev/null | while read -r line; do
    if echo "$line" | grep -qE "$ERROR_PATTERN"; then
        echo "$line" >> "$ERROR_LOG"
    fi
done
