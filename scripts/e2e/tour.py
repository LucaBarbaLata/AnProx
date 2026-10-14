#!/usr/bin/env python3
"""
End-to-end "tour" of the AnProx APK on an emulator or device, against the mock server.

It drives the real app through adb + uiautomator (no test framework inside the APK), so it
exercises exactly what users install — including the R8-minified release build:

  welcome → add a PVE server → sign in → dashboard → add a PBS → combined dashboard →
  resources → guest detail → start a VM → task finished → task log → snapshots → take a
  snapshot → tasks tab → light theme → no crash

Screenshots, UI dumps and logcat go to $ANPROX_E2E_OUT (default: e2e-output/).

Usage:  python3 scripts/e2e/tour.py path/to/app.apk
Env:    ANPROX_PACKAGE (default io.github.lucabarbalata.anprox)
        ANPROX_MOCK_HOST (default 10.0.2.2 = the emulator's view of the host machine)
"""
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

PKG = os.environ.get("ANPROX_PACKAGE", "io.github.lucabarbalata.anprox")
ACTIVITY = "io.github.lucabarbalata.anprox.MainActivity"
HOST = os.environ.get("ANPROX_MOCK_HOST", "10.0.2.2")
OUT = os.environ.get("ANPROX_E2E_OUT", "e2e-output")
STEPS = []


# ----------------------------------------------------------------------------- adb helpers

def adb(*args, check=True, timeout=120):
    result = subprocess.run(["adb", *args], capture_output=True, text=True, timeout=timeout)
    if check and result.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)} failed: {result.stderr.strip() or result.stdout.strip()}")
    return result.stdout


def screen_size():
    m = re.search(r"(\d+)x(\d+)", adb("shell", "wm", "size"))
    return (int(m.group(1)), int(m.group(2))) if m else (1080, 2400)


def dump():
    """Current UI hierarchy (uiautomator sees Compose through its accessibility tree)."""
    last = ""
    for _ in range(8):
        out = adb("shell", "uiautomator", "dump", "/sdcard/anprox-ui.xml", check=False)
        if "dumped" in out.lower():
            xml = adb("exec-out", "cat", "/sdcard/anprox-ui.xml")
            try:
                return ET.fromstring(xml)
            except ET.ParseError as e:
                last = f"parse error: {e}"
        else:
            last = out.strip()
        time.sleep(1)
    raise RuntimeError(f"uiautomator dump failed: {last}")


def center(node):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
    return (x1 + x2) // 2, (y1 + y2) // 2


def labels(node):
    return [v for v in (node.get("text"), node.get("content-desc")) if v]


def matches(root, text, exact):
    found = []
    for n in root.iter("node"):
        for value in labels(n):
            if (value == text) if exact else (text in value):
                found.append(n)
                break
    return found


def find(text, timeout=30, pick="first"):
    """Waits for a node whose text or content description is (or, failing that, contains) [text]."""
    deadline = time.time() + timeout
    root = None
    while time.time() < deadline:
        root = dump()
        found = matches(root, text, exact=True) or matches(root, text, exact=False)
        if found:
            if pick == "lowest":
                return max(found, key=lambda n: center(n)[1])
            return found[0]
        time.sleep(1)
    save_dump(root, f"missing-{slug(text)}")
    raise AssertionError(f"'{text}' did not appear within {timeout}s.\n{describe(root)}")


def describe(root):
    """Everything readable on screen, for the CI log (screenshots live in the artifact)."""
    if root is None:
        return "(no UI dump)"
    seen = []
    for n in root.iter("node"):
        for value in labels(n):
            seen.append(f"{value!r}@{n.get('bounds')}")
    return (f"keyboard requested: {ime_requested()}\n"
            f"focused window: {focused_window()}\n"
            f"app process: {adb('shell', 'pidof', PKG, check=False).strip() or 'not running'}\n"
            f"{crash_excerpt() or 'no crash in logcat'}\n"
            "On screen: " + ", ".join(seen))


def focused_window():
    out = adb("shell", "dumpsys", "window", check=False)
    m = re.search(r"mCurrentFocus=(.*)", out)
    return m.group(1).strip() if m else "unknown"


def crash_excerpt():
    log = adb("logcat", "-d", check=False)
    crash = re.search(r"FATAL EXCEPTION.*?(?=\n\S|\Z)", log, re.S)
    if crash and PKG in log[crash.start():crash.start() + 4000]:
        return "App crashed:\n" + log[crash.start():crash.start() + 4000]
    return None


def visible(text):
    root = dump()
    return bool(matches(root, text, exact=True) or matches(root, text, exact=False))


def tap_node(node):
    x, y = center(node)
    print(f"    tap {(labels(node) or [node.get('class')])[0]!r} at ({x}, {y})", flush=True)
    adb("shell", "input", "tap", str(x), str(y))
    time.sleep(1.0)


def tap(text, timeout=30, pick="first"):
    tap_node(find(text, timeout, pick))


def scroll_down():
    # Swipe in the upper half so an open keyboard can't swallow the gesture.
    w, h = screen_size()
    adb("shell", "input", "swipe", str(w // 2), str(int(h * 0.55)), str(w // 2), str(int(h * 0.18)), "400")
    time.sleep(1.0)


def nudge(dy):
    """Scrolls the content up by about [dy] pixels (down if negative), swiping mid-screen only."""
    w, h = screen_size()
    dy = max(-int(h * 0.45), min(int(h * 0.45), dy))
    start = int(h * 0.70) if dy > 0 else int(h * 0.25)
    adb("shell", "input", "swipe", str(w // 2), str(start), str(w // 2), str(start - dy), "900")
    time.sleep(1.0)


def first_match(root, text, pick="first"):
    found = matches(root, text, exact=True) or matches(root, text, exact=False)
    if not found:
        return None
    return max(found, key=lambda n: center(n)[1]) if pick == "lowest" else found[0]


def scroll_to(text, attempts=12, pick="first"):
    """
    Scrolls until [text] is on screen, then brings it towards mid-screen and waits until it
    stops moving. Use this for anything inside a scrolling list before tapping it.

    Why: the app draws edge to edge, so list items are also visible *under* the system
    navigation bar and the app's top and bottom bars. A tap there lands on the bar instead (on
    Android 14 the tour pressed the navigation bar's Back button this way and closed the server
    editor). At the very start or end of a list the item can't move further, but the list's
    content padding then keeps it clear of the bars.
    """
    w, h = screen_size()
    previous_y = None
    for _ in range(attempts):
        node = first_match(dump(), text, pick)
        if node is None:
            scroll_down()
            previous_y = None
            continue
        y = center(node)[1]
        if h * 0.2 <= y <= h * 0.7 or (previous_y is not None and abs(y - previous_y) < 12):
            time.sleep(0.5)
            settled = first_match(dump(), text, pick)
            if settled is not None and abs(center(settled)[1] - y) < 4:
                return settled
            previous_y = None   # still moving (fling): look again
            continue
        nudge(y - int(h * 0.45))
        previous_y = y
    return find(text, timeout=5, pick=pick)


def ime_requested():
    """Diagnostics only. On Android 14 this is true whenever a text field *asks* for a keyboard,
    even if none is on screen — so never use it to decide whether to press BACK."""
    out = adb("shell", "dumpsys", "input_method", check=False)
    return any(marker in out for marker in ("mInputShown=true", "mIsInputViewShown=true"))


def disable_soft_keyboards():
    """
    No on-screen keyboard during the tour: it covers half the screen and swallows swipes.
    Compose text fields still accept the key events `adb shell input text` sends (like a
    hardware keyboard).
    """
    for ime in adb("shell", "ime", "list", "-s", check=False).split():
        adb("shell", "ime", "disable", ime, check=False)


def fill(label, value, fallback_index=None, password=False):
    """
    Taps the text field labelled [label] and types [value]. Compose may merge a field's label
    into the EditText node, so fall back to the password field or the n-th EditText.
    """
    root = dump()
    found = matches(root, label, exact=True)
    fields = [n for n in root.iter("node") if n.get("class") == "android.widget.EditText"]
    if password:
        found = [n for n in fields if n.get("password") == "true"] or found
    if found:
        node = found[0]
    elif fallback_index is not None and len(fields) > fallback_index:
        node = fields[fallback_index]
    else:
        save_dump(root, f"field-{slug(label)}")
        raise AssertionError(f"text field '{label}' not found")
    tap_node(node)
    adb("shell", "input", "text", value.replace(" ", "%s"))
    time.sleep(0.6)


def back():
    adb("shell", "input", "keyevent", "4")
    time.sleep(1.2)


def screenshot(name):
    path = os.path.join(OUT, f"{name}.png")
    with open(path, "wb") as f:
        f.write(subprocess.run(["adb", "exec-out", "screencap", "-p"], capture_output=True, timeout=60).stdout)


def save_dump(root, name):
    if root is not None:
        with open(os.path.join(OUT, f"{name}.xml"), "wb") as f:
            f.write(ET.tostring(root))
    screenshot(name)


def slug(text):
    return re.sub(r"[^a-z0-9]+", "-", text.lower()).strip("-")[:40]


def assert_alive():
    pid = adb("shell", "pidof", PKG, check=False).strip()
    crash = crash_excerpt()
    if crash:
        raise AssertionError(crash)
    if not pid:
        raise AssertionError("App process is not running")


def step(name):
    def wrap(fn):
        def run():
            started = time.time()
            fn()
            assert_alive()
            STEPS.append((name, time.time() - started))
            print(f"  ✓ {name} ({time.time() - started:.1f}s)", flush=True)
        return run
    return wrap


# ----------------------------------------------------------------------------- the tour

def add_server(product_label, name, host_value):
    find("Add server")
    if product_label:
        tap(product_label)
    fill("Name", name, fallback_index=0)
    fill("Host or URL", host_value, fallback_index=1)
    tap_node(scroll_to("Allow insecure certificate"))
    tap_node(scroll_to("Save and sign in"))


def sign_in(password="anprox"):
    find("Remember password", timeout=30)
    fill("Password", password, password=True)
    # "Sign in" is both the screen title and the button below the form: make sure both show.
    for _ in range(4):
        if len(matches(dump(), "Sign in", exact=True)) >= 2:
            break
        scroll_down()
    tap_node(scroll_to("Sign in", pick="lowest"))


@step("Launch shows the welcome screen")
def launch():
    adb("shell", "am", "start", "-W", "-n", f"{PKG}/{ACTIVITY}")
    find("Welcome to AnProx", timeout=60)
    screenshot("01-welcome")


@step("Add a Proxmox VE server and sign in")
def add_pve():
    tap("Add your first server")
    add_server(None, "Homelab", HOST)
    sign_in()
    find("homelab", timeout=45)        # cluster name from the mock
    find("is offline", timeout=20)     # "Needs attention": node pve3 is offline
    time.sleep(3)                      # let gauges settle
    screenshot("02-dashboard")


@step("Add a Proxmox Backup Server and sign in")
def add_pbs():
    tap("Add server")                  # top bar action (content description)
    add_server("Proxmox Backup Server", "Backups", HOST)   # no port → 8007 is added automatically
    sign_in()
    scroll_to("store2")
    time.sleep(2)
    screenshot("03-dashboard-pve-and-pbs")


@step("Resources list across servers")
def resources():
    tap("Resources")
    find("k8s-master", timeout=30)
    screenshot("04-resources")


@step("Guest detail, start a VM and follow its task")
def guest_start():
    tap_node(scroll_to("win11"))
    find("Start", timeout=30)
    screenshot("05-guest-stopped")
    tap_node(scroll_to("Start"))
    find("finished", timeout=60)       # snackbar "VM start finished"
    screenshot("06-task-finished")
    tap("View log")
    find("TASK OK", timeout=30)
    screenshot("07-task-log")
    back()
    find("Shut down", timeout=30)      # the VM is running now
    back()


@step("Take a snapshot of the running VM")
def snapshots():
    tap_node(scroll_to("win11"))           # running since the previous step
    tap_node(scroll_to("Snapshots"))
    find("fresh-install", timeout=30)      # seeded by the mock
    screenshot("08-snapshots")
    tap("Take snapshot")
    find("Include RAM", timeout=15)        # offered because the VM is running
    screenshot("09-take-snapshot")
    tap("Take snapshot", pick="lowest")    # the dialog's button, below the screen's
    find("VM snapshot finished", timeout=60)
    find("snap-", timeout=20)              # the suggested name, now in the list
    screenshot("10-snapshot-taken")
    back()
    # Back on the guest screen, which keeps its scroll position at the Tools section.
    find("Save this state and roll back to it later", timeout=30)
    back()


@step("Tasks tab")
def tasks():
    tap("Tasks")
    find("VM start", timeout=30)
    screenshot("11-tasks")


@step("Switch to the light theme")
def light_theme():
    tap("More")
    tap("Settings")
    tap("Light")
    time.sleep(1)
    screenshot("12-settings-light")
    back()
    tap("Overview")
    find("homelab", timeout=30)
    time.sleep(2)
    screenshot("13-dashboard-light")


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    apk = sys.argv[1]
    os.makedirs(OUT, exist_ok=True)
    print(f"Installing {apk}", flush=True)
    adb("install", "-r", apk, timeout=300)
    adb("logcat", "-c", check=False)
    disable_soft_keyboards()
    w, h = screen_size()
    print(f"Screen {w}x{h}, {adb('shell', 'wm', 'density', check=False).strip().splitlines()[-1]}, "
          f"navigation mode {adb('shell', 'settings', 'get', 'secure', 'navigation_mode', check=False).strip()} "
          "(0 = three buttons, 2 = gestures)", flush=True)
    print("Tour:", flush=True)
    ok = False
    try:
        for s in (launch, add_pve, add_pbs, resources, guest_start, snapshots, tasks, light_theme):
            s()
        ok = True
    finally:
        with open(os.path.join(OUT, "logcat.txt"), "w") as f:
            f.write(adb("logcat", "-d", check=False))
        summary = os.environ.get("GITHUB_STEP_SUMMARY")
        if summary:
            with open(summary, "a") as f:
                api = adb("shell", "getprop", "ro.build.version.sdk", check=False).strip()
                f.write(f"### End-to-end tour (API {api}): {'passed ✅' if ok else 'FAILED ❌'}\n\n")
                for name, secs in STEPS:
                    f.write(f"- ✓ {name} ({secs:.1f}s)\n")
                f.write("\nScreenshots are in the `e2e-*` artifact.\n\n")
    print("Tour passed." if ok else "Tour failed.")


if __name__ == "__main__":
    main()
