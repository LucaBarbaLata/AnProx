#!/usr/bin/env python3
"""
End-to-end "tour" of the AnProx APK on an emulator or device, against the mock server.

It drives the real app through adb + uiautomator (no test framework inside the APK), so it
exercises exactly what users install — including the R8-minified release build:

  welcome → add a PVE server → sign in → dashboard → add a PBS → combined dashboard →
  resources → guest detail → start a VM → task finished → task log → tasks tab →
  light theme → no crash

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
    raise AssertionError(f"'{text}' did not appear within {timeout}s")


def visible(text):
    root = dump()
    return bool(matches(root, text, exact=True) or matches(root, text, exact=False))


def tap_node(node):
    x, y = center(node)
    adb("shell", "input", "tap", str(x), str(y))
    time.sleep(1.0)


def tap(text, timeout=30, pick="first"):
    tap_node(find(text, timeout, pick))


def scroll_down():
    w, h = screen_size()
    adb("shell", "input", "swipe", str(w // 2), str(int(h * 0.75)), str(w // 2), str(int(h * 0.30)), "350")
    time.sleep(1.0)


def scroll_to(text, attempts=8):
    for _ in range(attempts):
        root = dump()
        found = matches(root, text, exact=True) or matches(root, text, exact=False)
        if found:
            return found[0]
        scroll_down()
    return find(text, timeout=5)


def keyboard_shown():
    return "mInputShown=true" in adb("shell", "dumpsys", "input_method", check=False)


def hide_keyboard():
    if keyboard_shown():
        adb("shell", "input", "keyevent", "4")  # BACK closes the keyboard only
        time.sleep(0.8)


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
    hide_keyboard()


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
    log = adb("logcat", "-d", check=False)
    crash = re.search(r"FATAL EXCEPTION.*?(?=\n\S|\Z)", log, re.S)
    if crash and PKG in log[crash.start():crash.start() + 4000]:
        raise AssertionError("App crashed:\n" + log[crash.start():crash.start() + 4000])
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
    tap("Sign in", pick="lowest")


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
    tap("Start")
    find("finished", timeout=60)       # snackbar "VM start finished"
    screenshot("06-task-finished")
    tap("View log")
    find("TASK OK", timeout=30)
    screenshot("07-task-log")
    back()
    find("Shut down", timeout=30)      # the VM is running now
    back()


@step("Tasks tab")
def tasks():
    tap("Tasks")
    find("VM start", timeout=30)
    screenshot("08-tasks")


@step("Switch to the light theme")
def light_theme():
    tap("More")
    tap("Settings")
    tap("Light")
    time.sleep(1)
    screenshot("09-settings-light")
    back()
    tap("Overview")
    find("homelab", timeout=30)
    time.sleep(2)
    screenshot("10-dashboard-light")


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    apk = sys.argv[1]
    os.makedirs(OUT, exist_ok=True)
    print(f"Installing {apk}", flush=True)
    adb("install", "-r", apk, timeout=300)
    adb("logcat", "-c", check=False)
    print("Tour:", flush=True)
    ok = False
    try:
        for s in (launch, add_pve, add_pbs, resources, guest_start, tasks, light_theme):
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
