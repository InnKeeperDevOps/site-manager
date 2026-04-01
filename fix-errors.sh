#!/bin/bash
# Monitors error.log for new errors and sends them to Claude CLI to fix.
# Runs every 5 minutes via a loop. Tracks last-processed byte offset.
# Logs activity to error-fixer.log.

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ERROR_LOG="$SCRIPT_DIR/error.log"
FIXER_LOG="$SCRIPT_DIR/error-fixer.log"
STATE_FILE="$SCRIPT_DIR/.error-fixer-offset"
LOCK_FILE="/tmp/error-fixer.lock"
INTERVAL=300  # 5 minutes

log() {
    echo "$(date -u +"%Y-%m-%dT%H:%M:%SZ") $*" | tee -a "$FIXER_LOG"
}

# Prevent multiple instances
if [ -f "$LOCK_FILE" ]; then
    lock_pid=$(cat "$LOCK_FILE" 2>/dev/null)
    if kill -0 "$lock_pid" 2>/dev/null; then
        echo "Already running (PID $lock_pid)"
        exit 1
    fi
    rm -f "$LOCK_FILE"
fi
echo $$ > "$LOCK_FILE"
trap 'rm -f "$LOCK_FILE"; exit 0' SIGTERM SIGINT EXIT

log "[ERROR-FIXER] Starting error fixer (interval=${INTERVAL}s, PID=$$)"

# Filter out noise lines that aren't real errors
filter_real_errors() {
    grep -viE \
        'DefaultSecurityFilterChain|Will secure any request|INFO |DEBUG |TRACE ' | \
    grep -iE '\[ERROR\]|ERROR|Exception|FATAL|BUILD FAILED|NullPointer|ClassNotFound|NoSuchBean|StackOverflow|OutOfMemory'
}

while true; do
    # Get current error.log size
    if [ ! -f "$ERROR_LOG" ]; then
        sleep "$INTERVAL"
        continue
    fi

    current_size=$(stat -c %s "$ERROR_LOG" 2>/dev/null || echo 0)
    last_offset=$(cat "$STATE_FILE" 2>/dev/null || echo 0)

    # Handle log rotation (file shrunk)
    if [ "$current_size" -lt "$last_offset" ]; then
        last_offset=0
    fi

    if [ "$current_size" -le "$last_offset" ]; then
        log "[ERROR-FIXER] No new errors (offset=$last_offset, size=$current_size)"
        sleep "$INTERVAL"
        continue
    fi

    # Read new bytes from error.log
    new_bytes=$((current_size - last_offset))
    new_errors=$(tail -c +"$((last_offset + 1))" "$ERROR_LOG" | head -c "$new_bytes" | filter_real_errors)

    # Update offset regardless
    echo "$current_size" > "$STATE_FILE"

    if [ -z "$new_errors" ]; then
        log "[ERROR-FIXER] New bytes but no actionable errors (offset=$current_size)"
        sleep "$INTERVAL"
        continue
    fi

    error_count=$(echo "$new_errors" | wc -l)
    log "[ERROR-FIXER] Found $error_count new error(s), sending to Claude for fix"

    # Truncate to avoid oversized prompts
    truncated_errors=$(echo "$new_errors" | head -30)

    prompt="You are maintaining the site-manager Spring Boot application at /home/claude/site-manager.
The following NEW errors were found in the application logs:

$truncated_errors

INSTRUCTIONS:
1. Analyze these errors to determine root cause
2. Read the relevant source files to understand the issue
3. Fix the code if possible - edit the Java/HTML/JS source files directly
4. After fixing, run: cd /home/claude/site-manager && ./gradlew build -x test
5. If the build succeeds, commit with a clear message explaining the fix
6. If the errors are transient (e.g. network timeouts, temporary resource issues), just note that - don't change code
7. Be conservative - only fix clear bugs, don't refactor or add features
8. Do NOT push - only commit locally

Respond with a brief summary of what you found and what you did."

    log "[ERROR-FIXER] Invoking Claude CLI..."
    response=$(claude --dangerously-skip-permissions \
        -p "$prompt" \
        --model sonnet \
        --output-format text \
        2>&1)
    exit_code=$?

    if [ $exit_code -eq 0 ]; then
        # Truncate response for log
        summary=$(echo "$response" | head -20)
        log "[ERROR-FIXER] Claude completed (exit=$exit_code):"
        echo "$summary" >> "$FIXER_LOG"
    else
        log "[ERROR-FIXER] Claude failed (exit=$exit_code): $(echo "$response" | head -5)"
    fi

    log "[ERROR-FIXER] Cycle complete, sleeping ${INTERVAL}s"
    sleep "$INTERVAL"
done
