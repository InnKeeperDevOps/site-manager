#!/usr/bin/env python3
"""Textual TUI dashboard for the Site Manager application."""

import sqlite3
import os
import sys
import time
import signal
import subprocess
import threading
import json
from datetime import datetime, timezone
from pathlib import Path

from textual.app import App, ComposeResult
from textual.widgets import (
    Header, Footer, TabbedContent, TabPane, DataTable,
    Static, Input, Label, RichLog, Button, ProgressBar,
)
from textual.containers import Horizontal, Vertical, ScrollableContainer
from textual.screen import Screen, ModalScreen
from textual.reactive import reactive
from textual import on, work
from textual.binding import Binding
from textual.suggester import SuggestFromList
from rich.text import Text
from rich.table import Table
from rich import box

DB_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "data", "sitemanager.db")
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
PID_FILE = "/tmp/site-manager-auto-update.pid"
CLAUDE_LOG_FILE = os.path.join(SCRIPT_DIR, "claude-output.log")

STATUS_COLORS = {
    "MERGED": "green",
    "DEV_COMPLETE": "cyan",
    "FINAL_REVIEW": "yellow",
    "IN_PROGRESS": "blue",
    "PENDING": "white",
    "DENIED": "red",
    "TIMED_OUT": "magenta",
    "DRAFT": "dim white",
}

PRIORITY_COLORS = {
    "HIGH": "red bold",
    "MEDIUM": "yellow",
    "LOW": "dim white",
}

SERVICES = {
    "app": {
        "name": "Spring Boot App (auto-update)",
        "pid_file": PID_FILE,
        "start_cmd": f"nohup bash {SCRIPT_DIR}/auto-update.sh > {SCRIPT_DIR}/app.log 2>&1 & echo $!",
        "grep_pattern": "auto-update.sh",
        "log_file": os.path.join(SCRIPT_DIR, "app.log"),
    },
    "extractor": {
        "name": "Error Extractor",
        "pid_file": None,
        "start_cmd": f"nohup bash {SCRIPT_DIR}/extract-errors.sh > /dev/null 2>&1 & echo $!",
        "grep_pattern": "extract-errors.sh",
        "log_file": os.path.join(SCRIPT_DIR, "error.log"),
    },
}


# ── DB helpers ──────────────────────────────────────────────────────

def get_db():
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    return conn


def ts_to_str(ts):
    if not ts:
        return "N/A"
    try:
        dt = datetime.fromtimestamp(ts / 1000, tz=timezone.utc)
        return dt.strftime("%Y-%m-%d %H:%M")
    except (OSError, ValueError):
        return "N/A"


def time_ago(ts):
    if not ts:
        return "N/A"
    try:
        now = time.time() * 1000
        diff_sec = (now - ts) / 1000
        if diff_sec < 60:
            return f"{int(diff_sec)}s ago"
        elif diff_sec < 3600:
            return f"{int(diff_sec / 60)}m ago"
        elif diff_sec < 86400:
            return f"{int(diff_sec / 3600)}h ago"
        else:
            return f"{int(diff_sec / 86400)}d ago"
    except (OSError, ValueError):
        return "N/A"


def get_settings(conn):
    c = conn.cursor()
    c.execute("SELECT * FROM site_settings LIMIT 1")
    row = c.fetchone()
    if row:
        return dict(row)
    return {"site_name": "Site Manager", "claude_model": "unknown", "target_repo_url": ""}


# ── Service management ───────────────────────────────────────────────

def get_service_pid(svc_key):
    svc = SERVICES[svc_key]
    if svc["pid_file"] and os.path.exists(svc["pid_file"]):
        try:
            pid = int(open(svc["pid_file"]).read().strip())
            os.kill(pid, 0)
            return pid
        except (ValueError, OSError, ProcessLookupError):
            pass
    try:
        result = subprocess.run(
            ["pgrep", "-f", svc["grep_pattern"]],
            capture_output=True, text=True,
        )
        if result.returncode == 0:
            pids = result.stdout.strip().split("\n")
            return int(pids[0]) if pids[0] else None
    except (subprocess.TimeoutExpired, ValueError):
        pass
    return None


def get_service_status(svc_key):
    pid = get_service_pid(svc_key)
    if pid is None:
        return False, None, {}
    try:
        result = subprocess.run(
            ["ps", "-p", str(pid), "-o", "%cpu,%mem,rss,etime", "--no-headers"],
            capture_output=True, text=True,
        )
        if result.returncode == 0:
            parts = result.stdout.strip().split()
            if len(parts) >= 4:
                return True, pid, {
                    "cpu": parts[0], "mem": parts[1],
                    "rss": parts[2], "uptime": parts[3],
                }
        return True, pid, {}
    except subprocess.TimeoutExpired:
        return True, pid, {}


def start_service(svc_key):
    svc = SERVICES[svc_key]
    running, pid, _ = get_service_status(svc_key)
    if running:
        return False, f"{svc['name']} is already running (PID {pid})"
    try:
        for f in [os.path.join(SCRIPT_DIR, "app.log"), os.path.join(SCRIPT_DIR, "error.log")]:
            Path(f).touch(exist_ok=True)
        result = subprocess.run(
            svc["start_cmd"], shell=True, capture_output=True, text=True,
            cwd=SCRIPT_DIR,
        )
        new_pid = result.stdout.strip().split("\n")[-1].strip()
        return True, f"{svc['name']} started (PID {new_pid})"
    except Exception as e:
        return False, f"Failed to start {svc['name']}: {e}"


def stop_service(svc_key):
    svc = SERVICES[svc_key]
    running, pid, _ = get_service_status(svc_key)
    if not running:
        return False, f"{svc['name']} is not running"
    try:
        os.kill(pid, signal.SIGTERM)
        for _ in range(20):
            time.sleep(0.5)
            try:
                os.kill(pid, 0)
            except OSError:
                if svc["pid_file"] and os.path.exists(svc["pid_file"]):
                    os.remove(svc["pid_file"])
                return True, f"{svc['name']} stopped (was PID {pid})"
        os.kill(pid, signal.SIGKILL)
        if svc["pid_file"] and os.path.exists(svc["pid_file"]):
            os.remove(svc["pid_file"])
        return True, f"{svc['name']} force-killed (was PID {pid})"
    except Exception as e:
        return False, f"Failed to stop {svc['name']}: {e}"


def restart_service(svc_key):
    running, _, _ = get_service_status(svc_key)
    if running:
        ok, msg = stop_service(svc_key)
        if not ok:
            return False, msg
        time.sleep(1)
    ok, msg = start_service(svc_key)
    return ok, msg


# ── Screens ──────────────────────────────────────────────────────────

class DetailScreen(ModalScreen):
    """Modal screen showing suggestion detail."""

    BINDINGS = [Binding("escape,q", "dismiss", "Close")]

    def __init__(self, suggestion_id: int):
        super().__init__()
        self.suggestion_id = suggestion_id

    def compose(self) -> ComposeResult:
        with Vertical(id="detail-container"):
            yield Static(id="detail-content")
            yield Button("Close [Esc]", id="close-btn", variant="primary")

    def on_mount(self) -> None:
        self.query_one("#detail-content", Static).update(self._build_detail())

    def _build_detail(self) -> Text:
        conn = get_db()
        try:
            c = conn.cursor()
            c.execute("SELECT * FROM suggestions WHERE id = ?", (self.suggestion_id,))
            row = c.fetchone()
            if not row:
                return Text(f"Suggestion #{self.suggestion_id} not found", style="red")

            t = Text()
            t.append(f"#{row['id']} ", style="bold dim")
            t.append(f"{row['title']}\n", style="bold white")
            t.append(f"\nStatus: ", style="dim")
            t.append(f"{row['status']}\n", style=STATUS_COLORS.get(row["status"], "white"))
            t.append(f"Priority: ", style="dim")
            t.append(f"{row['priority'] or 'N/A'}\n", style=PRIORITY_COLORS.get(row["priority"] or "", "dim"))
            t.append(f"Author: ", style="dim")
            t.append(f"{row['author_name'] or 'N/A'}\n", style="white")
            t.append(f"Created: ", style="dim")
            t.append(f"{ts_to_str(row['created_at'])}\n", style="white")
            t.append(f"Last Activity: ", style="dim")
            t.append(f"{time_ago(row['last_activity_at'])}\n", style="white")

            if row["pr_url"]:
                t.append(f"PR: ", style="dim")
                t.append(f"{row['pr_url']}\n", style="cyan underline")

            if row["description"]:
                desc = row["description"]
                if len(desc) > 400:
                    desc = desc[:397] + "..."
                t.append(f"\n{desc}\n", style="white")

            if row["plan_display_summary"]:
                summary = row["plan_display_summary"]
                if len(summary) > 500:
                    summary = summary[:497] + "..."
                t.append(f"\nPlan Summary:\n{summary}\n", style="dim")

            c.execute(
                "SELECT title, status FROM plan_tasks WHERE suggestion_id = ? ORDER BY task_order",
                (self.suggestion_id,)
            )
            tasks = c.fetchall()
            if tasks:
                t.append(f"\nTasks ({len(tasks)}):\n", style="bold")
                icons = {"COMPLETED": "✓", "PENDING": "○", "IN_PROGRESS": "►", "REVIEWING": "◎", "FAILED": "✗"}
                icon_styles = {"COMPLETED": "green", "PENDING": "yellow", "IN_PROGRESS": "blue", "REVIEWING": "cyan", "FAILED": "red"}
                for task in tasks:
                    icon = icons.get(task["status"], "?")
                    style = icon_styles.get(task["status"], "white")
                    title = (task["title"] or "(untitled)")[:60]
                    t.append(f"  {icon} ", style=style)
                    t.append(f"{title}\n")

            c.execute(
                "SELECT sender_name, sender_type, content, created_at FROM suggestion_messages "
                "WHERE suggestion_id = ? ORDER BY created_at DESC LIMIT 5",
                (self.suggestion_id,)
            )
            msgs = c.fetchall()
            if msgs:
                t.append(f"\nRecent Messages:\n", style="bold")
                for m in reversed(msgs):
                    sender = m["sender_name"] or m["sender_type"]
                    content = (m["content"] or "")[:120]
                    sender_style = "cyan" if m["sender_type"] == "SYSTEM" else "yellow"
                    t.append(f"  {sender}: ", style=sender_style)
                    t.append(f"{content}\n")

            return t
        finally:
            conn.close()

    @on(Button.Pressed, "#close-btn")
    def close(self) -> None:
        self.dismiss()


CSS = """
#detail-container {
    background: $surface;
    border: thick $primary;
    padding: 1 2;
    margin: 2 4;
    height: auto;
    max-height: 90vh;
}

#detail-content {
    height: auto;
}

#status-bar {
    height: 1;
    background: $panel;
    color: $text-muted;
    padding: 0 1;
}

#command-input {
    dock: bottom;
    height: 3;
}

.stat-panel {
    border: round $primary;
    height: auto;
    min-height: 12;
    padding: 0 1;
}

.stat-label {
    color: $text-muted;
    width: 16;
}

.stat-value {
    text-style: bold;
}

#services-tab {
    height: 1fr;
}

#claude-log {
    border: round $accent;
    height: 1fr;
    min-height: 8;
}

#claude-input-row {
    height: 3;
    dock: bottom;
}

#claude-msg-input {
    width: 1fr;
}

#claude-send-btn {
    width: 10;
}

#progress-bar {
    width: 20;
}

.tab-content {
    height: 1fr;
}

SuggestionsStats, TaskStats, ActivityStats {
    width: 1fr;
}

DataTable {
    height: 1fr;
}
"""


class SuggestionsStats(Static):
    def on_mount(self) -> None:
        self.refresh_data()

    def refresh_data(self) -> None:
        conn = get_db()
        try:
            c = conn.cursor()
            c.execute("SELECT status, COUNT(*) FROM suggestions GROUP BY status")
            counts = dict(c.fetchall())
        finally:
            conn.close()

        total = sum(counts.values())
        merged = counts.get("MERGED", 0)
        in_prog = counts.get("IN_PROGRESS", 0)
        denied = counts.get("DENIED", 0)
        dev_complete = counts.get("DEV_COMPLETE", 0)
        final_review = counts.get("FINAL_REVIEW", 0)
        timed_out = counts.get("TIMED_OUT", 0)
        rate = f"{merged / total * 100:.0f}%" if total > 0 else "0%"

        t = Text()
        t.append("Suggestions\n\n", style="bold")
        rows = [
            ("Total", str(total), "bold white"),
            ("Merged", str(merged), "green"),
            ("In Progress", str(in_prog), "blue"),
            ("Dev Complete", str(dev_complete), "cyan"),
            ("Final Review", str(final_review), "yellow"),
            ("Denied", str(denied), "red"),
            ("Timed Out", str(timed_out), "magenta"),
            ("Success Rate", rate, "bold green"),
        ]
        for label, value, style in rows:
            t.append(f"  {label:<14}", style="dim")
            t.append(f"{value}\n", style=style)
        self.update(t)


class TaskStats(Static):
    def on_mount(self) -> None:
        self.refresh_data()

    def refresh_data(self) -> None:
        conn = get_db()
        try:
            c = conn.cursor()
            c.execute("SELECT status, COUNT(*) FROM plan_tasks GROUP BY status")
            counts = dict(c.fetchall())
        finally:
            conn.close()

        total = sum(counts.values())
        completed = counts.get("COMPLETED", 0)
        pending = counts.get("PENDING", 0)
        reviewing = counts.get("REVIEWING", 0)
        in_prog = counts.get("IN_PROGRESS", 0)
        pct = int(completed / total * 100) if total > 0 else 0
        bar_w = 18
        filled = int(bar_w * pct / 100)
        bar = "█" * filled + "░" * (bar_w - filled)

        t = Text()
        t.append("Plan Tasks\n\n", style="bold")
        t.append(f"  {'Total':<14}", style="dim")
        t.append(f"{total}\n", style="bold white")
        t.append(f"  {'Completed':<14}", style="dim")
        t.append(f"{completed}\n", style="green")
        t.append(f"  {'Pending':<14}", style="dim")
        t.append(f"{pending}\n", style="yellow")
        t.append(f"  {'Reviewing':<14}", style="dim")
        t.append(f"{reviewing}\n", style="cyan")
        t.append(f"  {'In Progress':<14}", style="dim")
        t.append(f"{in_prog}\n", style="blue")
        t.append(f"\n  ")
        t.append(bar, style="green")
        t.append(f" {pct}%\n")
        self.update(t)


class ActivityStats(Static):
    def on_mount(self) -> None:
        self.refresh_data()

    def refresh_data(self) -> None:
        conn = get_db()
        try:
            c = conn.cursor()
            c.execute("SELECT COUNT(*) FROM suggestion_messages")
            total_msgs = c.fetchone()[0]
            c.execute("SELECT COUNT(DISTINCT suggestion_id) FROM suggestion_messages")
            active_threads = c.fetchone()[0]
            c.execute("SELECT COUNT(*) FROM app_users")
            user_count = c.fetchone()[0]
            c.execute("SELECT COUNT(*) FROM suggestions WHERE pr_url IS NOT NULL")
            pr_count = c.fetchone()[0]
        finally:
            conn.close()

        db_size = os.path.getsize(DB_PATH) if os.path.exists(DB_PATH) else 0
        db_size_mb = db_size / (1024 * 1024)

        t = Text()
        t.append("Activity\n\n", style="bold")
        rows = [
            ("Messages", str(total_msgs), "bold white"),
            ("Active Threads", str(active_threads), "cyan"),
            ("Users", str(user_count), "yellow"),
            ("PRs Created", str(pr_count), "green"),
            ("DB Size", f"{db_size_mb:.1f} MB", "dim"),
        ]
        for label, value, style in rows:
            t.append(f"  {label:<14}", style="dim")
            t.append(f"{value}\n", style=style)
        self.update(t)


class ServicesWidget(Static):
    def on_mount(self) -> None:
        self.refresh_data()

    def refresh_data(self) -> None:
        t = Table(box=box.ROUNDED, show_lines=False, padding=(0, 1))
        t.add_column("Service", min_width=28, no_wrap=True)
        t.add_column("Status", width=10)
        t.add_column("PID", width=8, justify="right")
        t.add_column("CPU%", width=6, justify="right")
        t.add_column("MEM%", width=6, justify="right")
        t.add_column("RSS", width=10, justify="right")
        t.add_column("Uptime", width=14)

        for key, svc in SERVICES.items():
            running, pid, info = get_service_status(key)
            status_str = "[green]RUNNING[/]" if running else "[red]DOWN[/]"
            pid_str = str(pid) if pid else "-"
            cpu = info.get("cpu", "-")
            mem = info.get("mem", "-")
            rss_kb = info.get("rss", "0")
            try:
                rss_val = int(rss_kb)
                rss_str = f"{rss_val // 1024} MB" if rss_val > 1024 else f"{rss_val} KB"
            except (ValueError, TypeError):
                rss_str = "-"
            uptime = info.get("uptime", "-")
            t.add_row(svc["name"], status_str, pid_str, cpu, mem, rss_str, uptime)

        try:
            result = subprocess.run(["free", "-m"], capture_output=True, text=True)
            for line in result.stdout.splitlines():
                if line.startswith("Mem:"):
                    parts = line.split()
                    total_mem, used = int(parts[1]), int(parts[2])
                    pct = used * 100 // total_mem if total_mem else 0
                    t.add_row(
                        "[dim]System Memory[/]", f"[dim]{pct}%[/]", "-",
                        "-", f"[dim]{pct}[/]", f"[dim]{used}MB[/]", f"[dim]{total_mem}MB total[/]",
                    )
        except Exception:
            pass

        try:
            result = subprocess.run(["df", "-h", "/home/claude"], capture_output=True, text=True)
            lines = result.stdout.strip().splitlines()
            if len(lines) >= 2:
                parts = lines[1].split()
                t.add_row(
                    "[dim]Disk (/home/claude)[/]", f"[dim]{parts[4]}[/]", "-",
                    "-", "-", f"[dim]{parts[2]}[/]", f"[dim]{parts[1]} total[/]",
                )
        except Exception:
            pass

        self.update(t)


# ── Main App ─────────────────────────────────────────────────────────

class SiteManagerApp(App):
    CSS = CSS
    TITLE = "Site Manager Dashboard"
    BINDINGS = [
        Binding("ctrl+q", "quit", "Quit"),
        Binding("r", "refresh", "Refresh"),
        Binding("escape", "focus_command", "Command"),
    ]

    def __init__(self):
        super().__init__()
        self.claude_history = []  # list of (role, text, timestamp)
        self.claude_working = False
        self.claude_current_prompt = ""
        self.claude_start_time = None
        self.claude_thinking_snippet = ""
        self.claude_is_thinking = False
        self.claude_response_so_far = ""
        self.status_message = ""
        self.list_page = 0
        self.list_filter = None
        self.list_sort = "id"

    def compose(self) -> ComposeResult:
        conn = get_db()
        settings = get_settings(conn)
        conn.close()
        site_name = settings.get("site_name") or "Site Manager"
        model = settings.get("claude_model") or "unknown"
        repo = (settings.get("target_repo_url") or "").split("/")[-1].replace(".git", "")

        yield Header(show_clock=True)
        yield Label(f" {site_name}  |  Model: {model}  |  Repo: {repo}", id="subtitle")

        with TabbedContent(initial="dashboard"):
            with TabPane("Dashboard", id="dashboard"):
                with Horizontal():
                    yield SuggestionsStats(classes="stat-panel")
                    yield TaskStats(classes="stat-panel")
                    yield ActivityStats(classes="stat-panel")
                with Horizontal():
                    yield Static(id="recent-activity", classes="stat-panel")
                    yield ServicesWidget(id="services-widget", classes="stat-panel")
                    yield Static(id="claude-panel", classes="stat-panel")

            with TabPane("Suggestions", id="suggestions"):
                yield DataTable(id="suggestions-table", cursor_type="row")

            with TabPane("Activity", id="activity"):
                yield DataTable(id="activity-table", cursor_type="row")

            with TabPane("Services", id="services"):
                yield ServicesWidget(id="services-full")
                with Horizontal(id="claude-input-row"):
                    yield Input(placeholder='Claude command (e.g. "check app logs")', id="claude-msg-input")
                    yield Button("Send", id="claude-send-btn", variant="primary")
                yield RichLog(id="claude-log", highlight=True, markup=True)

            with TabPane("Claude", id="claude"):
                with Horizontal(id="claude-input-row"):
                    yield Input(placeholder="Send message to Claude...", id="claude-msg-input2")
                    yield Button("Send", id="claude-send-btn2", variant="primary")
                yield RichLog(id="claude-log2", highlight=True, markup=True)

            with TabPane("Help", id="help"):
                yield Static(self._build_help(), id="help-content")

        yield Label("", id="status-bar")
        yield Input(
            placeholder="Command: [c] Claude  [v <id>] Detail  [start/stop/restart <svc>]  [f <status>]  [n/p] pages  [r] Refresh  [q] Quit",
            id="command-input",
            suggester=SuggestFromList([
                "q", "r", "n", "p",
                "c ",
                "v ",
                "f MERGED", "f DEV_COMPLETE", "f FINAL_REVIEW", "f IN_PROGRESS",
                "f PENDING", "f DENIED", "f TIMED_OUT", "f DRAFT", "f CLEAR",
                "s id", "s votes", "s activity", "s created", "s status",
                "start app", "start extractor",
                "stop app", "stop extractor",
                "restart app", "restart extractor", "restart all",
            ], case_sensitive=False),
        )
        yield Footer()

    def _build_help(self) -> Text:
        t = Text()
        t.append("Navigation\n", style="bold underline")
        t.append("  Ctrl+Q    ", style="bold cyan"); t.append("Quit\n")
        t.append("  R         ", style="bold cyan"); t.append("Refresh\n")
        t.append("  Tab       ", style="bold cyan"); t.append("Switch tabs\n")

        t.append("\nCommand Bar (press Escape to focus)\n", style="bold underline")
        t.append("  c <msg>   ", style="bold cyan"); t.append("Send message to Claude\n")
        t.append("  v <id>    ", style="bold cyan"); t.append("View suggestion detail\n")
        t.append("  start <svc>    ", style="bold cyan"); t.append("Start service (app, extractor)\n")
        t.append("  stop <svc>     ", style="bold cyan"); t.append("Stop service\n")
        t.append("  restart <svc>  ", style="bold cyan"); t.append("Restart service\n")
        t.append("  restart all    ", style="bold cyan"); t.append("Restart all services\n")
        t.append("  f <status>     ", style="bold cyan"); t.append("Filter suggestions list\n")
        t.append("  f clear        ", style="bold cyan"); t.append("Clear filter\n")
        t.append("  s <field>      ", style="bold cyan"); t.append("Sort: id, votes, activity, created, status\n")
        t.append("  n / p          ", style="bold cyan"); t.append("Next/prev page in suggestions\n")
        t.append("  q              ", style="bold cyan"); t.append("Quit\n")

        t.append("\nStatus Values\n", style="bold underline")
        for status, color in STATUS_COLORS.items():
            t.append(f"  {status:<16}", style=color)
        t.append("\n")
        return t

    def on_mount(self) -> None:
        self.set_interval(30, self._auto_refresh)
        self.set_interval(2, self._tick_claude_panel)
        self._populate_suggestions_table()
        self._populate_activity_table()
        self._refresh_recent_activity()
        self._refresh_claude_panel()
        self.query_one("#command-input", Input).focus()

    def _auto_refresh(self) -> None:
        self._refresh_stats()
        self._refresh_recent_activity()
        self._refresh_claude_panel()

    def action_refresh(self) -> None:
        self._refresh_stats()
        self._populate_suggestions_table()
        self._populate_activity_table()
        self._refresh_recent_activity()
        self._refresh_claude_panel()
        svc = self.query_one("#services-widget", ServicesWidget)
        svc.refresh_data()
        try:
            svc2 = self.query_one("#services-full", ServicesWidget)
            svc2.refresh_data()
        except Exception:
            pass
        self.set_status("[green]Refreshed[/]")

    def action_focus_command(self) -> None:
        self.query_one("#command-input", Input).focus()

    def _refresh_stats(self) -> None:
        for widget_type in (SuggestionsStats, TaskStats, ActivityStats):
            for w in self.query(widget_type):
                w.refresh_data()

    def _refresh_recent_activity(self) -> None:
        conn = get_db()
        try:
            c = conn.cursor()
            c.execute("""
                SELECT sm.sender_name, sm.sender_type, sm.content, sm.created_at, sm.suggestion_id
                FROM suggestion_messages sm
                ORDER BY sm.created_at DESC
                LIMIT 10
            """)
            msgs = c.fetchall()
        finally:
            conn.close()

        t = Text()
        t.append("Recent Activity\n\n", style="bold")
        for m in msgs:
            when = time_ago(m["created_at"])
            sid = f"#{m['suggestion_id']}"
            sender = m["sender_name"] or m["sender_type"] or "?"
            content = (m["content"] or "")[:80]
            sender_style = "cyan" if m["sender_type"] == "SYSTEM" else "yellow"
            t.append(f"  {when:<8}", style="dim")
            t.append(f"{sid:<6}", style="dim")
            t.append(f"{sender}: ", style=sender_style)
            t.append(f"{content}\n")

        try:
            self.query_one("#recent-activity", Static).update(t)
        except Exception:
            pass

    def _tick_claude_panel(self) -> None:
        if self.claude_working:
            self._refresh_claude_panel()

    def _refresh_claude_panel(self) -> None:
        t = Text()
        t.append("Claude Activity\n\n", style="bold white")

        # Live status block
        if self.claude_working:
            elapsed = time.time() - self.claude_start_time if self.claude_start_time else 0
            prompt_preview = (self.claude_current_prompt or "")[:60]
            if len(self.claude_current_prompt) > 60:
                prompt_preview += "..."
            t.append("  STATUS  ", style="bold black on yellow")
            if self.claude_is_thinking:
                t.append(f" THINKING  ({elapsed:.0f}s)\n", style="bold yellow")
                t.append("  Prompt:  ", style="dim")
                t.append(f"{prompt_preview}\n", style="white")
                if self.claude_thinking_snippet:
                    snippet = self.claude_thinking_snippet[:90]
                    t.append("  Thought: ", style="dim cyan")
                    t.append(f"{snippet}...\n", style="cyan")
            else:
                t.append(f" RESPONDING  ({elapsed:.0f}s)\n", style="bold green")
                t.append("  Prompt:  ", style="dim")
                t.append(f"{prompt_preview}\n", style="white")
                if self.claude_response_so_far:
                    preview = self.claude_response_so_far[:90]
                    t.append("  Writing: ", style="dim green")
                    t.append(f"{preview}...\n", style="green")
            t.append("\n")
        else:
            t.append("  STATUS  ", style="bold black on green")
            t.append(" IDLE\n\n", style="bold green")

        # Recent history
        history = self.claude_history[-4:]
        if not history:
            t.append("  Ready. Use command bar: c <message>\n", style="dim")
        else:
            for entry in history:
                role = entry[0]
                text = entry[1]
                ts = entry[2] if len(entry) > 2 else None
                elapsed_str = ""
                if role == "claude" and len(entry) > 3:
                    elapsed_str = f" [{entry[3]:.1f}s]"
                when = time_ago(ts * 1000) if ts else ""
                if role == "user":
                    t.append("  You", style="bold yellow")
                    t.append(f" {when}: ", style="dim yellow")
                    display = text if len(text) <= 80 else text[:77] + "..."
                    t.append(f"{display}\n", style="white")
                else:
                    t.append("  Claude", style="bold cyan")
                    t.append(f"{elapsed_str} {when}: ", style="dim cyan")
                    lines = text.split("\n")
                    preview = lines[0][:80]
                    if len(lines) > 1 or len(text) > 80:
                        preview += "..."
                    t.append(f"{preview}\n", style="white")
        try:
            self.query_one("#claude-panel", Static).update(t)
        except Exception:
            pass

    def _populate_suggestions_table(self) -> None:
        try:
            table = self.query_one("#suggestions-table", DataTable)
        except Exception:
            return

        conn = get_db()
        try:
            c = conn.cursor()
            query = "SELECT id, title, status, up_votes, down_votes, priority, pr_url, last_activity_at FROM suggestions"
            params = []
            if self.list_filter:
                query += " WHERE status = ?"
                params.append(self.list_filter)
            sort_map = {
                "id": "id DESC",
                "votes": "(up_votes - down_votes) DESC",
                "activity": "last_activity_at DESC",
                "created": "created_at DESC",
                "status": "status ASC",
            }
            query += f" ORDER BY {sort_map.get(self.list_sort, 'id DESC')}"
            query += f" LIMIT 50 OFFSET {self.list_page * 50}"
            c.execute(query, params)
            rows = c.fetchall()
        finally:
            conn.close()

        table.clear(columns=True)
        table.add_columns("#", "Title", "Status", "Priority", "Votes", "PR", "Activity")
        for row in rows:
            title = (row["title"] or "(untitled)")[:48]
            status = row["status"] or "UNKNOWN"
            priority = row["priority"] or "-"
            votes = row["up_votes"] - row["down_votes"]
            vote_str = f"+{votes}" if votes > 0 else str(votes)
            has_pr = "Y" if row["pr_url"] else "-"
            activity = time_ago(row["last_activity_at"])
            table.add_row(
                str(row["id"]), title, status, priority, vote_str, has_pr, activity,
                key=str(row["id"]),
            )

    def _populate_activity_table(self) -> None:
        try:
            table = self.query_one("#activity-table", DataTable)
        except Exception:
            return

        conn = get_db()
        try:
            c = conn.cursor()
            c.execute("""
                SELECT sm.sender_name, sm.sender_type, sm.content, sm.created_at, sm.suggestion_id, s.title
                FROM suggestion_messages sm
                JOIN suggestions s ON s.id = sm.suggestion_id
                ORDER BY sm.created_at DESC
                LIMIT 100
            """)
            msgs = c.fetchall()
        finally:
            conn.close()

        table.clear(columns=True)
        table.add_columns("Time", "Sug#", "From", "Message")
        for m in msgs:
            when = time_ago(m["created_at"])
            sid = f"#{m['suggestion_id']}"
            sender = m["sender_name"] or m["sender_type"] or "?"
            content = (m["content"] or "")[:100]
            table.add_row(when, sid, sender, content)

    def set_status(self, msg: str) -> None:
        try:
            self.query_one("#status-bar", Label).update(msg)
        except Exception:
            pass

    @on(Input.Submitted, "#command-input")
    def handle_command(self, event: Input.Submitted) -> None:
        cmd = event.value.strip()
        event.input.clear()
        if not cmd:
            return
        self._process_command(cmd)

    @on(Button.Pressed, "#claude-send-btn")
    def send_claude_services(self) -> None:
        inp = self.query_one("#claude-msg-input", Input)
        msg = inp.value.strip()
        if msg:
            inp.clear()
            self._send_to_claude(msg)

    @on(Input.Submitted, "#claude-msg-input")
    def submit_claude_services(self, event: Input.Submitted) -> None:
        msg = event.value.strip()
        if msg:
            event.input.clear()
            self._send_to_claude(msg)

    @on(Button.Pressed, "#claude-send-btn2")
    def send_claude_tab(self) -> None:
        inp = self.query_one("#claude-msg-input2", Input)
        msg = inp.value.strip()
        if msg:
            inp.clear()
            self._send_to_claude(msg)

    @on(Input.Submitted, "#claude-msg-input2")
    def submit_claude_tab(self, event: Input.Submitted) -> None:
        msg = event.value.strip()
        if msg:
            event.input.clear()
            self._send_to_claude(msg)

    def _process_command(self, cmd: str) -> None:
        cmd_lower = cmd.lower()

        if cmd_lower == "q":
            self.exit()
        elif cmd_lower == "r":
            self.action_refresh()
        elif cmd_lower == "n":
            self.list_page += 1
            self._populate_suggestions_table()
            self.set_status(f"Page {self.list_page + 1}")
        elif cmd_lower == "p":
            self.list_page = max(0, self.list_page - 1)
            self._populate_suggestions_table()
            self.set_status(f"Page {self.list_page + 1}")
        elif cmd_lower.startswith("v "):
            try:
                sid = int(cmd.split()[1])
                self.push_screen(DetailScreen(sid))
            except (ValueError, IndexError):
                self.set_status("[red]Usage: v <suggestion_id>[/]")
        elif cmd_lower.startswith("f "):
            arg = cmd_lower[2:].strip().upper()
            if arg == "CLEAR":
                self.list_filter = None
                self.set_status("[dim]Filter cleared[/]")
            else:
                self.list_filter = arg
                self.set_status(f"Filter: {arg}")
            self.list_page = 0
            self._populate_suggestions_table()
        elif cmd_lower.startswith("s ") and not cmd_lower.startswith("start") and not cmd_lower.startswith("stop"):
            arg = cmd_lower[2:].strip()
            if arg in ("id", "votes", "activity", "created", "status"):
                self.list_sort = arg
                self._populate_suggestions_table()
                self.set_status(f"Sort: {arg}")
            else:
                self.set_status(f"[red]Unknown sort: {arg}. Use: id, votes, activity, created, status[/]")
        elif cmd_lower.startswith("start "):
            svc_key = cmd_lower.split(None, 1)[1].strip()
            if svc_key in SERVICES:
                self.set_status(f"[yellow]Starting {svc_key}...[/]")
                self._run_service_cmd(start_service, svc_key)
            else:
                self.set_status(f"[red]Unknown service: {svc_key}[/]")
        elif cmd_lower.startswith("stop "):
            svc_key = cmd_lower.split(None, 1)[1].strip()
            if svc_key in SERVICES:
                self.set_status(f"[yellow]Stopping {svc_key}...[/]")
                self._run_service_cmd(stop_service, svc_key)
            else:
                self.set_status(f"[red]Unknown service: {svc_key}[/]")
        elif cmd_lower.startswith("restart "):
            svc_key = cmd_lower.split(None, 1)[1].strip()
            if svc_key == "all":
                self.set_status("[yellow]Restarting all services...[/]")
                self._run_service_cmd_all()
            elif svc_key in SERVICES:
                self.set_status(f"[yellow]Restarting {svc_key}...[/]")
                self._run_service_cmd(restart_service, svc_key)
            else:
                self.set_status(f"[red]Unknown service: {svc_key}[/]")
        elif cmd_lower.startswith("c "):
            msg = cmd[2:].strip()
            if msg:
                self._send_to_claude(msg)
        elif cmd_lower == "c":
            self.set_status("[dim]Use: c <message>[/]")
        else:
            self.set_status(f"[red]Unknown command: {cmd}[/]")

    @work(thread=True)
    def _run_service_cmd(self, fn, svc_key: str) -> None:
        ok, msg = fn(svc_key)
        style = "green" if ok else "red"
        self.call_from_thread(self.set_status, f"[{style}]{msg}[/]")
        self.call_from_thread(self._refresh_services)

    @work(thread=True)
    def _run_service_cmd_all(self) -> None:
        msgs = []
        for key in SERVICES:
            ok, msg = restart_service(key)
            msgs.append(f"[{'green' if ok else 'red'}]{msg}[/]")
        self.call_from_thread(self.set_status, " | ".join(msgs))
        self.call_from_thread(self._refresh_services)

    def _refresh_services(self) -> None:
        for w in self.query(ServicesWidget):
            w.refresh_data()

    def _send_to_claude(self, message: str) -> None:
        self.claude_history.append(("user", message, time.time()))
        self.claude_working = True
        self.claude_current_prompt = message
        self.claude_start_time = time.time()
        self.claude_thinking_snippet = ""
        self.claude_is_thinking = False
        self.claude_response_so_far = ""
        self._refresh_claude_panel()
        self._update_claude_logs()
        self.set_status("[yellow]Claude is working...[/]")
        self._claude_worker(message)

    def _append_verbose_line(self, line: str) -> None:
        for log_id in ("#claude-log", "#claude-log2"):
            try:
                log = self.query_one(log_id, RichLog)
                log.write(Text.from_markup(line))
            except Exception:
                pass

    @work(thread=True)
    def _claude_worker(self, message: str) -> None:
        system_context = (
            "You are managing the site-manager application at /home/claude/site-manager. "
            "The user is interacting with you through a dashboard terminal. "
            "You can make code changes, commit and push them, restart services, etc. "
            "Be concise in responses (under 500 chars unless showing code). "
            "When making changes, actually edit the files and run the commands. "
            "After making code changes, offer to commit and push. "
            "Available services: Spring Boot app (auto-update.sh), Error Extractor (extract-errors.sh). "
            "To restart the app: stop auto-update.sh processes, then relaunch. "
            "To commit: git add, git commit, git push origin main. "
            "Current directory: /home/claude/site-manager "
            "IMPORTANT: Do exactly what the user requests and nothing more. Do not get creative or make changes beyond what was explicitly asked. If the user asks to move something in a specific way, do only that exact move."
        )
        response_parts = []
        session_ts = datetime.now().isoformat()
        try:
            with open(CLAUDE_LOG_FILE, "a") as log_f:
                log_f.write(f"\n{'='*60}\n")
                log_f.write(f"Session: {session_ts}\n")
                log_f.write(f"Prompt: {message}\n")
                log_f.write(f"{'='*60}\n")

            self.call_from_thread(self._append_verbose_line, f"[bold yellow]>[/] {message}")

            proc = subprocess.Popen(
                [
                    "claude", "--dangerously-skip-permissions",
                    "-p", f"{system_context}\n\nUser request: {message}",
                    "--model", "sonnet",
                    "--output-format", "stream-json",
                    "--verbose",
                ],
                stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                text=True, cwd=SCRIPT_DIR,
            )
            for raw_line in proc.stdout:
                # Write every raw line to the log file
                with open(CLAUDE_LOG_FILE, "a") as log_f:
                    log_f.write(raw_line)

                line = raw_line.strip()
                if not line:
                    continue
                try:
                    ev = json.loads(line)
                except json.JSONDecodeError:
                    continue
                ev_type = ev.get("type", "")

                if ev_type == "assistant":
                    content = ev.get("message", {}).get("content", [])
                    for block in content:
                        btype = block.get("type", "")
                        if btype == "thinking":
                            thinking = block.get("thinking", "")
                            snippet = thinking[:120].replace("\n", " ") if thinking else ""
                            self.claude_is_thinking = True
                            self.claude_thinking_snippet = snippet
                            self.call_from_thread(self._refresh_claude_panel)
                            if snippet:
                                self.call_from_thread(
                                    self._append_verbose_line,
                                    f"[dim cyan]THINK[/] {snippet}",
                                )
                        elif btype == "text":
                            text_chunk = block.get("text", "")
                            response_parts.append(text_chunk)
                            self.claude_is_thinking = False
                            preview = "".join(response_parts)[:200].replace("\n", " ")
                            self.claude_response_so_far = preview
                            self.call_from_thread(self._refresh_claude_panel)
                        elif btype == "tool_use":
                            tool_name = block.get("name", "?")
                            tool_input = block.get("input", {})
                            input_str = json.dumps(tool_input, separators=(",", ":"))[:120]
                            self.call_from_thread(
                                self._append_verbose_line,
                                f"[bold blue]TOOL[/] [cyan]{tool_name}[/] [dim]{input_str}[/]",
                            )

                elif ev_type == "user":
                    # tool_result blocks come back as user messages
                    content = ev.get("message", {}).get("content", [])
                    if isinstance(content, list):
                        for block in content:
                            if isinstance(block, dict) and block.get("type") == "tool_result":
                                result_content = block.get("content", "")
                                if isinstance(result_content, list):
                                    result_content = " ".join(
                                        c.get("text", "") for c in result_content
                                        if isinstance(c, dict)
                                    )
                                snippet = str(result_content)[:100].replace("\n", " ")
                                is_err = block.get("is_error", False)
                                color = "red" if is_err else "green"
                                self.call_from_thread(
                                    self._append_verbose_line,
                                    f"[{color}]{'ERR' if is_err else 'OK '}[/] [dim]{snippet}[/]",
                                )

                elif ev_type == "system":
                    subtype = ev.get("subtype", "")
                    if subtype == "init":
                        tools = ev.get("tools", [])
                        tool_names = ", ".join(t.get("name", "?") for t in tools[:8])
                        self.call_from_thread(
                            self._append_verbose_line,
                            f"[dim]INIT tools: {tool_names}[/]",
                        )

                elif ev_type == "result":
                    final = ev.get("result", "")
                    if final:
                        response_parts = [final]
                        self.claude_response_so_far = final[:200].replace("\n", " ")
                    stats = ev.get("stats", {})
                    if stats:
                        turns = stats.get("num_turns", "?")
                        cost = stats.get("cost_usd", None)
                        cost_str = f"  cost=${cost:.4f}" if cost is not None else ""
                        self.call_from_thread(
                            self._append_verbose_line,
                            f"[dim]DONE  turns={turns}{cost_str}[/]",
                        )

            proc.wait()
            response = "".join(response_parts).strip() if response_parts else "(no response)"
            if proc.returncode != 0:
                stderr = proc.stderr.read().strip()
                if stderr and not response_parts:
                    response = f"[error]: {stderr[:300]}"
        except Exception as e:
            response = f"[error: {e}]"

        if len(response) > 3000:
            response = response[:2900] + "\n... (truncated)"

        elapsed = time.time() - self.claude_start_time if self.claude_start_time else 0
        self.claude_history.append(("claude", response, time.time(), elapsed))
        self.claude_working = False
        self.claude_is_thinking = False
        self.claude_response_so_far = ""
        self.call_from_thread(self._refresh_claude_panel)
        self.call_from_thread(self._update_claude_logs)
        self.call_from_thread(self.set_status, f"[green]Claude responded ({elapsed:.1f}s)[/]")

    def _update_claude_logs(self) -> None:
        for log_id in ("#claude-log", "#claude-log2"):
            try:
                log = self.query_one(log_id, RichLog)
                log.clear()
                for entry in self.claude_history[-20:]:
                    role = entry[0]
                    text = entry[1]
                    ts = entry[2] if len(entry) > 2 else None
                    elapsed_str = f" [{entry[3]:.1f}s]" if role == "claude" and len(entry) > 3 else ""
                    when = f" ({time_ago(ts * 1000)})" if ts else ""
                    if role == "user":
                        header = Text(f"You{when}: ", style="bold yellow")
                        log.write(header + Text(text))
                    else:
                        header = Text(f"Claude{elapsed_str}{when}: ", style="bold cyan")
                        log.write(header + Text(text))
                    log.write("")
            except Exception:
                pass

    @on(DataTable.RowSelected, "#suggestions-table")
    def on_suggestion_selected(self, event: DataTable.RowSelected) -> None:
        if event.row_key and event.row_key.value:
            try:
                sid = int(event.row_key.value)
                self.push_screen(DetailScreen(sid))
            except (ValueError, TypeError):
                pass


def main():
    app = SiteManagerApp()
    app.run()


if __name__ == "__main__":
    main()
