#!/bin/bash

CLAUDE_RUN_AS_USER=claude
AUTO_UPDATE_BRANCH="${AUTO_UPDATE_BRANCH:-main}"
AUTO_UPDATE_INTERVAL="${AUTO_UPDATE_INTERVAL:-60}"
PID_FILE="/tmp/site-manager-auto-update.pid"

# Validate branch name
if ! echo "$AUTO_UPDATE_BRANCH" | grep -qE '^[a-zA-Z0-9/_.-]+$'; then
    echo "$(date -u +"%Y-%m-%dT%H:%M:%SZ") [ERROR] Invalid AUTO_UPDATE_BRANCH: '$AUTO_UPDATE_BRANCH'" >&2
    exit 1
fi

log() {
    echo "$(date -u +"%Y-%m-%dT%H:%M:%SZ") $*"
}

start_app() {
    log "Starting application..."
    setsid ./gradlew bootRun --no-daemon &
    local pid=$!
    echo "$pid" > "$PID_FILE"
    log "Application started with PID $pid"
}

stop_app() {
    if [ ! -f "$PID_FILE" ]; then
        log "No PID file found, nothing to stop"
        return
    fi

    local pid
    pid=$(cat "$PID_FILE")
    log "Sending SIGTERM to process group $pid..."
    kill -- -"$pid" 2>/dev/null

    local waited=0
    while kill -0 "$pid" 2>/dev/null; do
        if [ "$waited" -ge 30 ]; then
            log "Process did not stop after 30s, sending SIGKILL..."
            kill -9 -- -"$pid" 2>/dev/null || kill -9 "$pid" 2>/dev/null
            break
        fi
        sleep 1
        waited=$((waited + 1))
    done

    rm -f "$PID_FILE"

    # setsid creates a new process group, so the Java child may still be running
    # even after the setsid parent exits. Wait for port 8080 to be released.
    waited=0
    while ss -tlnp 2>/dev/null | grep -q ':8080 '; do
        if [ "$waited" -ge 30 ]; then
            log "Port 8080 still in use after 30s, force-killing Java process..."
            fuser -k 8080/tcp 2>/dev/null
            sleep 2
            break
        fi
        sleep 1
        waited=$((waited + 1))
    done

    log "Application stopped"
}

check_for_updates() {
    git fetch origin "$AUTO_UPDATE_BRANCH" 2>/dev/null
    local local_ref
    local remote_ref
    local_ref=$(git rev-parse HEAD)
    remote_ref=$(git rev-parse "origin/$AUTO_UPDATE_BRANCH")
    if [ "$local_ref" != "$remote_ref" ]; then
        return 0
    fi
    return 1
}

handle_signal() {
    log "Signal received, shutting down..."
    stop_app
    exit 0
}

trap handle_signal SIGTERM SIGINT

start_app

while true; do
    sleep "$AUTO_UPDATE_INTERVAL"

    if [ -f "$PID_FILE" ]; then
        local_pid=$(cat "$PID_FILE")
        if ! kill -0 "$local_pid" 2>/dev/null; then
            log "App process died unexpectedly, restarting..."
            rm -f "$PID_FILE"
            start_app
            continue
        fi
    fi

    if check_for_updates; then
        log "Update detected on $AUTO_UPDATE_BRANCH, pulling and restarting..."
        stop_app
        if git pull --rebase origin "$AUTO_UPDATE_BRANCH"; then
            log "Pull succeeded, starting updated application..."
            start_app
        else
            log "[ERROR] git pull failed, restarting existing version..."
            start_app
        fi
    fi
done
