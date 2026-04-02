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

SCRIPT_PATH="$(readlink -f "${BASH_SOURCE[0]}")"
SCRIPT_MTIME=$(stat -c %Y "$SCRIPT_PATH" 2>/dev/null || echo 0)
LOG_FILE="/home/claude/site-manager/app.log"
ERROR_LOG="/home/claude/site-manager/error.log"

# Pattern matches actual errors, not class names containing "Exception"
# Matches: log lines with ERROR/FATAL level, stack traces, BUILD FAILED,
# auto-update [ERROR] lines, and git fatal errors
ERROR_PATTERN='\] (ERROR|FATAL) |\[ERROR\]|^\tat |^Caused by:|^fatal:|BUILD FAILED'

# Process existing errors first (overwrites stale entries)
if [ -f "$LOG_FILE" ]; then
    grep -E "$ERROR_PATTERN" "$LOG_FILE" > "$ERROR_LOG" 2>/dev/null
fi

# Then follow new lines, checking for script changes periodically
LINE_COUNT=0
tail -n 0 -F "$LOG_FILE" 2>/dev/null | while read -r line; do
    if echo "$line" | grep -qE "$ERROR_PATTERN"; then
        echo "$line" >> "$ERROR_LOG"
    fi
    # Every 100 lines, check if the script has been updated on disk
    LINE_COUNT=$(( (LINE_COUNT + 1) % 100 ))
    if [ "$LINE_COUNT" -eq 0 ]; then
        NEW_MTIME=$(stat -c %Y "$SCRIPT_PATH" 2>/dev/null || echo 0)
        if [ "$NEW_MTIME" != "$SCRIPT_MTIME" ]; then
            echo "Script updated on disk, exiting to allow restart with new version." >&2
            exit 0
        fi
    fi
done
