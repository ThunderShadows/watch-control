#!/usr/bin/env python3
"""
Watch Coach desktop companion (v0.4).

Receives keystroke / mouse events from the Watch Coach Android app over the
local network and performs them with pynput.

Usage:
  python3 -m venv .venv
  .venv/bin/pip install pynput
  .venv/bin/python watch_coach_companion.py --token YOUR_TOKEN

The token must match the one shown in Watch Coach → Settings → Laptop companion.
The phone and this computer must be on the same LAN. Default bind is
0.0.0.0:8765 (HTTP, no TLS — LAN only).

On Linux, run this from an X11/Xorg session. pynput cannot inject keys or
move the pointer on a Wayland session.

Keys the phone may send: right, left, up, down, space, enter, playpause,
next, prev, volup, voldown, f5, escape.
"""
from __future__ import annotations

import argparse
import json
import os
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


def load_pynput():
    try:
        from pynput.keyboard import Controller as KeyCtl
        from pynput.keyboard import Key
        from pynput.mouse import Button
        from pynput.mouse import Controller as MouseCtl
    except ImportError as exc:
        sys.stderr.write(
            "pynput is required. Install with:\n"
            "  python3 -m pip install pynput\n"
            f"({exc})\n"
        )
        sys.exit(1)
    return KeyCtl(), MouseCtl(), Key, Button


KEYB, MOUSE, Key, Button = None, None, None, None  # set in main


KEY_MAP = {
    "right": "right",
    "left": "left",
    "up": "up",
    "down": "down",
    "space": "space",
    "enter": "enter",
    "esc": "esc",
    "escape": "esc",
    "tab": "tab",
    "f5": "f5",
    "playpause": "playpause",
    "next": "next",
    "prev": "prev",
    "volup": "volup",
    "voldown": "voldown",
}


def special(name):
    mapping = {
        "right": Key.right,
        "left": Key.left,
        "up": Key.up,
        "down": Key.down,
        "space": Key.space,
        "enter": Key.enter,
        "esc": Key.esc,
        "tab": Key.tab,
        "f5": Key.f5,
        "playpause": getattr(Key, "media_play_pause", Key.space),
        "next": getattr(Key, "media_next", Key.right),
        "prev": getattr(Key, "media_previous", Key.left),
        "volup": getattr(Key, "media_volume_up", Key.up),
        "voldown": getattr(Key, "media_volume_down", Key.down),
    }
    return mapping[name]


class Handler(BaseHTTPRequestHandler):
    token = ""

    def log_message(self, fmt, *args):
        sys.stderr.write("%s - %s\n" % (self.address_string(), fmt % args))

    def _auth_ok(self) -> bool:
        header = self.headers.get("Authorization", "")
        expected = "Bearer " + self.token
        return header == expected and bool(self.token)

    def _json(self, code: int, payload: dict):
        body = json.dumps(payload).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path in ("/", "/health", "/ping"):
            self._json(200, {"ok": True, "service": "watch-coach-companion"})
            return
        self._json(404, {"error": "not found"})

    def do_POST(self):
        if self.path != "/event":
            self._json(404, {"error": "not found"})
            return
        if not self._auth_ok():
            self._json(401, {"error": "bad token"})
            return
        length = int(self.headers.get("Content-Length", "0"))
        raw = self.rfile.read(length) if length else b"{}"
        try:
            data = json.loads(raw.decode("utf-8"))
        except json.JSONDecodeError:
            self._json(400, {"error": "invalid json"})
            return
        kind = data.get("type")
        try:
            if kind == "ping":
                self._json(200, {"ok": True})
                return
            if kind == "key":
                name = KEY_MAP.get(str(data.get("key", "")).lower())
                if not name:
                    self._json(400, {"error": "unknown key"})
                    return
                KEYB.press(special(name))
                KEYB.release(special(name))
                self._json(200, {"ok": True, "key": name})
                return
            if kind == "click":
                btn = Button.right if data.get("button") == "right" else Button.left
                MOUSE.click(btn)
                self._json(200, {"ok": True, "click": data.get("button", "left")})
                return
            if kind == "mouse":
                dx = int(data.get("dx") or 0)
                dy = int(data.get("dy") or 0)
                MOUSE.move(dx, dy)
                self._json(200, {"ok": True, "dx": dx, "dy": dy})
                return
            self._json(400, {"error": "unknown type"})
        except Exception as exc:  # noqa: BLE001 — surface to the phone
            self._json(500, {"error": str(exc)})


def main():
    global KEYB, MOUSE, Key, Button
    parser = argparse.ArgumentParser(description="Watch Coach laptop companion")
    parser.add_argument("--host", default="0.0.0.0", help="bind address")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--token", default=os.environ.get("WATCH_COACH_TOKEN", ""), help="shared token")
    args = parser.parse_args()
    if not args.token:
        sys.stderr.write("Pass --token (the value shown in Watch Coach → Settings → Laptop companion).\n")
        sys.exit(2)
    if os.environ.get("XDG_SESSION_TYPE", "").lower() == "wayland":
        sys.stderr.write(
            "Warning: this looks like a Wayland session. Key and mouse injection needs an "
            "X11/Xorg session, not Wayland. Log out, choose an Xorg session (for example "
            "“Ubuntu on Xorg”), and start the companion again.\n"
        )
    KEYB, MOUSE, Key, Button = load_pynput()
    Handler.token = args.token
    httpd = ThreadingHTTPServer((args.host, args.port), Handler)
    sys.stderr.write(
        "Watch Coach companion listening on http://%s:%s\n"
        "On the phone, set this computer's LAN IP and the same token.\n"
        % (args.host, args.port)
    )
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        sys.stderr.write("\nStopped.\n")


if __name__ == "__main__":
    main()
