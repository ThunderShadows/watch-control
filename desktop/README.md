# Watch Coach desktop companion

A small Python server that receives events from the Watch Coach Android app on your LAN and presses keys or moves the mouse with [pynput](https://pypi.org/project/pynput/).

Use this for presentation clicker keys (next / previous slide), play/pause, or the **phone-sensor** air-mouse fallback. GloryFit / UTE watches do **not** document raw accelerometer streaming, so air-mouse from the watch itself is not implemented.

## Setup

```bash
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
.venv/bin/python watch_coach_companion.py --token YOUR_TOKEN --port 8765
```

`YOUR_TOKEN` must match the token in Watch Coach → **Settings → Laptop companion**. Both devices must be on the same Wi-Fi network. On the phone, enter this computer's LAN IP and the same port, then tap **Ping laptop**.

macOS will prompt for Accessibility permission so pynput can press keys. Windows may prompt for a firewall allow on port 8765. The server speaks plain HTTP on the LAN only — do not expose it to the internet.

### Linux needs X11, not Wayland

pynput injects keys and mouse movement through X11. On a Wayland session it cannot drive the focused application.

1. Log out.
2. On the login screen, open the session menu (gear icon) and choose **Ubuntu on Xorg** or another **X11 / Xorg** session. Do not choose a Wayland session.
3. Confirm with `echo $XDG_SESSION_TYPE`. It should print `x11`.
4. Start the companion in that session.

If `$XDG_SESSION_TYPE` is `wayland`, the script prints a warning and key or mouse events will not land reliably.

## Protocol

`POST /event` with `Authorization: Bearer <token>` and JSON body:

- `{"type":"ping"}`
- `{"type":"key","key":"right"}` — `right`, `left`, `up`, `down`, `space`, `enter`, `f5`, `escape`, `playpause`, `next`, `prev`, `volup`, `voldown`
- `{"type":"click","button":"left"}`
- `{"type":"mouse","dx":10,"dy":-3}`

`GET /health` is unauthenticated and only used as a reachability check from a browser.
