#!/usr/bin/env python3
"""
End-to-end "tour" of the AnProx APK on an emulator or device, against the mock server.

It drives the real app through adb + uiautomator (no test framework inside the APK), so it
exercises exactly what users install — including the R8-minified release build:

  welcome → add a PVE server → sign in → dashboard → add a PBS → combined dashboard →
  resources → performance charts → guest detail → start a VM → task finished → task log → snapshots → take a
  snapshot → back up and restore → tasks tab → light theme → no crash

Screenshots, UI dumps and logcat go to $ANPROX_E2E_OUT (default: e2e-output/).

Usage:  python3 scripts/e2e/tour.py path/to/app.apk
Env:    ANPROX_PACKAGE (default io.github.lucabarbalata.anprox)
        ANPROX_MOCK_HOST (default 10.0.2.2 = the emulator's view of the host machine)
"""
import os
import re
import struct
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


def dump(attempts=8):
    """Current UI hierarchy (uiautomator sees Compose through its accessibility tree)."""
    last = ""
    for _ in range(attempts):
        # uiautomator waits for the screen to be idle; on recent Android its complaint goes to stderr.
        result = subprocess.run(["adb", "shell", "uiautomator", "dump", "/sdcard/anprox-ui.xml"],
                                capture_output=True, text=True, timeout=120)
        out = result.stdout + result.stderr
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
        # Off-screen nodes can be listed with empty bounds (seen on Android 8 while a column
        # scrolled); they can't be tapped, so they don't count as found.
        x1, y1, x2, y2 = bounds(n)
        if x2 <= x1 or y2 <= y1:
            continue
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


def find_any(texts, timeout=30):
    """Waits until one of [texts] is on screen (contains-match)."""
    deadline = time.time() + timeout
    root = None
    while time.time() < deadline:
        root = dump()
        for text in texts:
            found = matches(root, text, exact=False)
            if found:
                return found[0]
        time.sleep(1)
    save_dump(root, "missing-any")
    raise AssertionError(f"none of {texts} appeared within {timeout}s.\n{describe(root)}")


def visible(text):
    root = dump()
    return bool(matches(root, text, exact=True) or matches(root, text, exact=False))


def bounds(node):
    return tuple(map(int, re.findall(r"\d+", node.get("bounds"))))


def row_shows(name, text):
    """Whether the list row of [name] also shows [text] (the two overlap vertically)."""
    root = dump()
    row = first_match(root, name)
    if row is None:
        return False
    _, top, _, bottom = bounds(row)
    return any(bounds(n)[1] < bottom + 80 and bounds(n)[3] > top - 80 for n in matches(root, text, exact=True))


def wait_until(condition, what, timeout=30):
    """
    Waits for [condition] (checked against fresh UI dumps). Prefer this over waiting for a
    snackbar: a short one (4 s) can come and go between two dumps on a slow emulator.
    """
    deadline = time.time() + timeout
    while time.time() < deadline:
        if condition():
            return
        time.sleep(1)
    root = dump()
    save_dump(root, f"timeout-{slug(what)}")
    raise AssertionError(f"Timed out after {timeout}s waiting for {what}.\n{describe(root)}")


def tap_node(node):
    x, y = center(node)
    print(f"    tap {(labels(node) or [node.get('class')])[0]!r} at ({x}, {y})", flush=True)
    adb("shell", "input", "tap", str(x), str(y))
    time.sleep(1.0)


def long_press(node):
    x, y = center(node)
    print(f"    long-press {(labels(node) or [node.get('class')])[0]!r} at ({x}, {y})", flush=True)
    adb("shell", "input", "swipe", str(x), str(y), str(x), str(y), "900")
    time.sleep(1.0)


def tap(text, timeout=30, pick="first"):
    tap_node(find(text, timeout, pick))


def scroll_down():
    # Swipe in the upper half so an open keyboard can't swallow the gesture.
    w, h = screen_size()
    adb("shell", "input", "swipe", str(w // 2), str(int(h * 0.55)), str(w // 2), str(int(h * 0.18)), "400")
    time.sleep(1.0)


def scroll_up():
    w, h = screen_size()
    adb("shell", "input", "swipe", str(w // 2), str(int(h * 0.25)), str(w // 2), str(int(h * 0.62)), "400")
    time.sleep(1.0)


def signature(root):
    """What is on screen, to notice when a swipe no longer moves anything."""
    return tuple((tuple(labels(n)), n.get("bounds")) for n in root.iter("node") if labels(n))


def nudge(dy):
    """Scrolls the content up by about [dy] pixels (down if negative), swiping mid-screen only."""
    w, h = screen_size()
    dy = max(-int(h * 0.45), min(int(h * 0.45), dy))
    start = int(h * 0.70) if dy > 0 else int(h * 0.25)
    adb("shell", "input", "swipe", str(w // 2), str(start), str(w // 2), str(start - dy), "900")
    time.sleep(1.0)


def first_match(root, text, pick="first", exact=False):
    found = matches(root, text, exact=True) or ([] if exact else matches(root, text, exact=False))
    if not found:
        return None
    return max(found, key=lambda n: center(n)[1]) if pick == "lowest" else found[0]


def scroll_to(text, attempts=24, pick="first", exact=False):
    """
    Scrolls until [text] is on screen, then brings it towards mid-screen and waits until it
    stops moving. Use this for anything inside a scrolling list before tapping it.

    Why: the app draws edge to edge, so list items are also visible *under* the system
    navigation bar and the app's top and bottom bars. A tap there lands on the bar instead (on
    Android 14 the tour pressed the navigation bar's Back button this way and closed the server
    editor). At the very start or end of a list the item can't move further, but the list's
    content padding then keeps it clear of the bars.

    Scrolls down first; once the screen stops changing (end of the list) it scrolls up instead,
    so targets above the current scroll position are found too. With [exact], a text that only
    contains [text] doesn't count (a guest row's "Homelab · pve1 · …" is not the node pve1).
    """
    w, h = screen_size()
    previous_y = None
    upwards = False
    last_seen = None
    for _ in range(attempts):
        root = dump()
        node = first_match(root, text, pick, exact)
        if node is None:
            seen = signature(root)
            if seen and seen == last_seen and not upwards:
                upwards = True          # the swipe changed nothing: end of the list, turn around once
            last_seen = seen
            scroll_up() if upwards else scroll_down()
            previous_y = None
            continue
        y = center(node)[1]
        if h * 0.2 <= y <= h * 0.7 or (previous_y is not None and abs(y - previous_y) < 12):
            time.sleep(0.5)
            settled = first_match(dump(), text, pick, exact)
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
    # The AOSP keyboard can't always be switched off; the first time it shows for a URL field it
    # asks for the contacts, and that dialog covers the app. Deny it for good up front (granting
    # it made the keyboard's suggestions change what the tour typed).
    latin = "com.android.inputmethod.latin"
    adb("shell", "pm", "revoke", latin, "android.permission.READ_CONTACTS", check=False)
    adb("shell", "pm", "set-permission-flags", latin, "android.permission.READ_CONTACTS", "user-fixed", check=False)
    # On Android 14 it stays bound after "ime disable" and still edits what the tour types
    # (a trailing space, ".iso" completed to ".isolated"); switch the app off entirely.
    print(adb("shell", "pm", "disable-user", "--user", "0", latin, check=False).strip(), flush=True)


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
    if not password:
        complete_typing(label, value)


def fill_password(n, value):
    """Types [value] into the n-th password field on screen (a form may have several)."""
    root = dump()
    fields = [x for x in root.iter("node") if x.get("class") == "android.widget.EditText" and x.get("password") == "true"]
    if len(fields) <= n:
        save_dump(root, f"password-{n}")
        raise AssertionError(f"password field {n} not found")
    tap_node(fields[n])
    adb("shell", "input", "text", value)
    time.sleep(0.6)


def fix_field(value):
    """Finds the text field that holds the start of [value], moves to its end and types the rest."""
    root = dump()
    for n in root.iter("node"):
        text = n.get("text") or ""
        if n.get("class") == "android.widget.EditText" and text and value.startswith(text) and text != value:
            tap_node(n)
            adb("shell", "input", "keyevent", "KEYCODE_MOVE_END")
            missing = value[len(text):]
            print(f"    retyping {missing!r}", flush=True)
            adb("shell", "input", "text", missing.replace(" ", "%s"))
            time.sleep(0.8)
            return


def complete_typing(label, value, attempts=5):
    """
    Android 14 sometimes loses the last injected key (seen on CI: a URL arrived without its final
    letter). Once it showed up in the field first and was gone a second later, so the text has to
    read right twice, a moment apart. Types what is missing if the field holds the start of [value].
    """
    confirmed = False
    for _ in range(attempts):
        root = dump()
        texts = [n.get("text") or "" for n in root.iter("node") if n.get("class") == "android.widget.EditText"]
        if value in texts:
            if confirmed:
                return
            confirmed = True
            time.sleep(1.5)
            continue
        confirmed = False
        if not any(texts):
            # A dump taken while a dialog or the keyboard is (re)drawn can miss the fields.
            time.sleep(1.0)
            continue
        partial = [t for t in texts if t and value.startswith(t)]
        if not partial:
            return  # can't tell which field it is (or the app reformatted the text)
        missing = value[len(max(partial, key=len)):]
        print(f"    retyping {missing!r} into '{label}'", flush=True)
        adb("shell", "input", "text", missing.replace(" ", "%s"))
        time.sleep(0.6)


def back():
    adb("shell", "input", "keyevent", "4")
    time.sleep(1.2)


def wait_for_snackbar_to_close():
    """A task's result snackbar (with **View log**) stays 10 s and covers the bottom of the screen."""
    wait_until(lambda: not visible("View log"), "the result snackbar to close", timeout=20)


def leave_console(marker="Reconnect"):
    """
    Presses Back until the console (recognised by its top bar's [marker]) is gone. The first Back may only hide
    the soft keyboard, and while the terminal has focus its blinking cursor keeps uiautomator from
    ever seeing an idle screen, so a failed dump means "still there" too.
    """
    for _ in range(3):
        back()
        try:
            root = dump(attempts=2)
        except RuntimeError:
            continue
        if not matches(root, marker, exact=True):
            return
    raise AssertionError(f"Back didn't leave the console (still showing '{marker}')")


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


@step("Performance charts and guest agent info of a running VM")
def performance():
    tap_node(scroll_to("k8s-master"))
    # The Network card sits above the charts: check it first, before scrolling down.
    scroll_to("10.0.0.103")                # IP address reported by the guest agent
    find("Ubuntu 24.04.1 LTS", timeout=10)
    tap("Add to favourites")               # the star in the top bar; checked on the dashboard at the end
    find("Remove from favourites", timeout=10)
    tap_node(scroll_to("Week"))            # timeframe picker
    time.sleep(2)                          # reload for the new timeframe
    scroll_to("Disk I/O")                  # the last chart
    root = dump()
    readouts = [v for n in root.iter("node") for v in labels(n) if v.startswith(("Read ", "Write "))]
    if not readouts or all(v.endswith("—") for v in readouts):
        save_dump(root, "charts-without-data")
        raise AssertionError(f"Disk I/O chart shows no values: {readouts}\n{describe(root)}")
    screenshot("05-performance")
    back()
    find("Resources", timeout=20)


@step("Guest agent: freeze and thaw the filesystems, then trim them")
def agent():
    tap_node(scroll_to("k8s-master"))      # running, with the agent enabled
    tap_node(scroll_to("Guest agent"))
    find("Writable", timeout=30)
    tap("Freeze filesystems")
    find("Every write inside the VM waits", timeout=10)
    tap("Freeze filesystems", pick="lowest")   # the dialog's button
    find("Filesystems frozen: 2", timeout=30)
    find("Thaw filesystems", timeout=15)
    screenshot("05b-agent-frozen")
    tap("Thaw filesystems")
    find("Filesystems thawed: 2", timeout=30)
    find("Writable", timeout=15)
    tap_node(scroll_to("Trim filesystems", pick="lowest"))   # the button, below the card's title
    find("Trim finished", timeout=30)
    scroll_to("freed")                         # "/  3.00 GiB freed", under the button
    screenshot("05c-agent-trim")
    back()
    back()
    find("Resources", timeout=20)


@step("Guest detail, start a VM and follow its task")
def guest_start():
    tap_node(scroll_to("win11"))
    find("Start", timeout=30)
    screenshot("06-guest-stopped")
    tap_node(scroll_to("Start"))
    find("finished", timeout=60)       # snackbar "VM start finished"
    screenshot("07-task-finished")
    tap("View log")
    find("TASK OK", timeout=30)
    screenshot("08-task-log")
    back()
    find("Shut down", timeout=30)      # the VM is running now
    back()


@step("Take a snapshot of the running VM")
def snapshots():
    tap_node(scroll_to("win11"))           # running since the previous step
    tap_node(scroll_to("Snapshots"))
    find("fresh-install", timeout=30)      # seeded by the mock
    screenshot("09-snapshots")
    tap("Take snapshot")
    find("Include RAM", timeout=15)        # offered because the VM is running
    screenshot("10-take-snapshot")
    tap("Take snapshot", pick="lowest")    # the dialog's button, below the screen's
    find("VM snapshot finished", timeout=60)
    find("snap-", timeout=20)              # the suggested name, now in the list
    screenshot("11-snapshot-taken")
    wait_for_snackbar_to_close()
    tap_node(scroll_to("Options for fresh-install"))   # the seeded snapshot's menu
    tap("Edit description")
    replace_field("Description", "pre-25H2-baseline", fallback_index=0)
    tap("Save")
    find("Description of fresh-install saved", timeout=30)
    find("pre-25H2-baseline", timeout=15)  # shown under the snapshot after the reload
    back()
    # Back on the guest screen, which keeps its scroll position.
    scroll_to("Save this state and roll back to it later")
    back()


@step("Back up a container, then restore it as a new one")
def backups():
    tap_node(scroll_to("pihole"))
    tap_node(scroll_to("Backups"))
    find("Verified", timeout=30)           # the seeded Backup Server backup, verified
    screenshot("11b-backups")
    tap("Back up now")
    find("Backup Server", timeout=15)      # storage choice; the Backup Server is preselected
    screenshot("11c-back-up-now")
    tap("Back up", pick="lowest")          # the dialog's button
    find("Backup finished", timeout=60)
    tap_node(scroll_to("Restore"))         # newest backup first
    find("As a new container", timeout=15)
    find("105", timeout=15)                # the next free id, filled in
    tap("Start restore")
    find("CT restore finished", timeout=60)
    screenshot("11d-restored")
    back()
    scroll_to("Back up now and restore")   # back on the guest screen (it keeps its scroll position)
    back()


@step("Edit a container's options")
def options():
    tap_node(scroll_to("nginx-proxy"))
    tap_node(scroll_to("Options"))
    find("Hostname", timeout=30)           # containers call their name a hostname
    find("No unsaved changes.", timeout=15)
    tap_node(scroll_to("Start at boot"))
    wait_until(lambda: not visible("No unsaved changes."), "the switch to make the form dirty", timeout=15)
    tap_node(scroll_to("Save"))
    # Saved and reloaded: the form is clean again (the "Options saved" snackbar is short).
    wait_until(lambda: visible("No unsaved changes."), "the options to be saved", timeout=30)
    screenshot("11e-options")
    back()
    scroll_to("Name, tags, notes, start at boot, protection")  # back on the guest screen
    back()


@step("VM hardware: add a disk, and a memory change that waits for the next start")
def hardware():
    tap_node(scroll_to("win11"))           # running since the start step
    tap_node(scroll_to("Hardware"))
    find("Processor", timeout=30)          # section headers are upper-case on screen; this is a row
    find("SCSI 0", timeout=30)             # the seeded system disk
    tap_node(scroll_to("Add disk"))
    find("Bus", timeout=15)                # the dialog: SCSI on the first storage, 32 GiB
    screenshot("11e2-add-disk")
    tap("Add", pick="lowest")              # the dialog's button
    scroll_to("SCSI 1")                    # the new disk, once the task has finished
    tap_node(scroll_to("Memory"))
    tap_node(scroll_to("Ballooning"))      # turn it off
    tap("Save", pick="lowest")
    # The VM is running: Proxmox keeps the change for the next start.
    find("After restart", timeout=30)
    screenshot("11e3-hardware")
    back()
    scroll_to("CPU, memory, disks, CD/DVD, network, boot order")  # back on the guest screen
    back()


def console_probe(tag):
    """
    Prints what the console shows, to the job log: the WebView's bounds, the text it exposes, a
    pixel from its middle, the app's "Console details" and its console log lines. A blank console
    with a live connection (seen on a real Proxmox VE 8) looks "Connected" otherwise.
    """
    try:
        root = dump(attempts=3)
        views = [n for n in root.iter("node") if n.get("class") == "android.webkit.WebView"]
        if views:
            x1, y1, x2, y2 = bounds(views[0])
            texts = [t for n in views[0].iter("node") for t in [n.get("text") or ""] if t.strip()]
            print(f"    [{tag}] webview [{x1},{y1}][{x2},{y2}], text: {' | '.join(texts)[:400]!r}", flush=True)
            raw = subprocess.run(["adb", "exec-out", "screencap"], capture_output=True, timeout=60).stdout
            w, h = struct.unpack("<II", raw[:8])
            offset = len(raw) - w * h * 4
            for fx, fy in ((0.5, 0.5), (0.1, 0.05), (0.5, 0.2)):
                px, py = int(x1 + (x2 - x1) * fx), int(y1 + (y2 - y1) * fy)
                i = offset + (py * w + px) * 4
                print(f"    [{tag}] pixel at ({px},{py}): #{raw[i]:02x}{raw[i + 1]:02x}{raw[i + 2]:02x}", flush=True)
        else:
            print(f"    [{tag}] no WebView in the dump", flush=True)
    except Exception as e:
        print(f"    [{tag}] probe failed: {e}", flush=True)
    details = ""
    try:
        tap("Console details", timeout=10)
        root = dump(attempts=3)
        details = " | ".join(t for n in root.iter("node") for t in [n.get("text") or ""] if t.strip())
        print(f"    [{tag}] details: {details[:1500]!r}", flush=True)
        tap("Close", timeout=10)
    except Exception as e:
        print(f"    [{tag}] details failed: {e}", flush=True)
    for line in adb("logcat", "-d", "-s", "AnProxConsole:*", check=False).strip().splitlines()[-12:]:
        print(f"    [{tag}] {line}", flush=True)
    adb("logcat", "-c", check=False)
    return details


def assert_console_fills(details, what):
    """The page reports its terminal or screen size; a 0 px high viewport left one row / 0 px."""
    sizes = re.findall(rf"{what} (?:size )?(\d+)x(\d+)", details)
    if not sizes:
        raise AssertionError(f"the console details don't report the {what} size: {details[:300]!r}")
    w, h = map(int, sizes[-1])
    minimum = 5 if what == "terminal" else 100
    if h < minimum:
        raise AssertionError(f"the console's {what} is {w}x{h}: the page has no height")


@step("Container console: terminal through the app's own connection, with the extra keys")
def console():
    tap_node(scroll_to("nginx-proxy"))
    tap_node(scroll_to("Console"))
    # The status under the title comes from the app's connection to the (mock) termproxy.
    # An emulator whose Android System WebView is too old for the bundled pages says so instead.
    node = find_any(["Connected", "Android System WebView"], timeout=45)
    if "Connected" in " ".join(labels(node)):
        time.sleep(2)
        assert_console_fills(console_probe("terminal"), "terminal")
        tap("Esc")                         # extra key row → sent to the terminal
        tap("Keyboard")                    # focuses the terminal and asks for the soft keyboard
        time.sleep(1)
    screenshot("11f-console")
    leave_console()
    scroll_to("The container's terminal")  # back on the guest screen
    back()


@step("VM screen: noVNC bundled in the app, over the app's connection")
def vm_console():
    tap_node(scroll_to("k8s-master"))
    tap_node(scroll_to("Console"))
    # noVNC completes the VNC handshake (with the generated password) and reports the desktop name.
    node = find_any(["Connected · k8s-master", "Android System WebView"], timeout=45)
    if "Connected" in " ".join(labels(node)):
        time.sleep(2)
        assert_console_fills(console_probe("vnc"), "screen")  # the mock's screen is #1e293b
    screenshot("11f2-vm-console")
    leave_console()
    scroll_to("The VM's screen (noVNC)")
    back()


@step("Cloud-init settings of a VM")
def cloud_init():
    tap_node(scroll_to("k8s-master"))
    tap_node(scroll_to("Cloud-init"))
    find("ubuntu", timeout=30)             # the user from the VM's config
    scroll_to("10.0.0.103/24")             # net0's static address
    screenshot("11f3-cloud-init")
    back()
    scroll_to("User, password, SSH keys, IP addresses, DNS")  # back on the guest screen
    back()


@step("Select two guests on the list and start the stopped one")
def bulk():
    long_press(scroll_to("backup-client"))  # stopped container: starts the selection
    find("1 selected", timeout=10)
    tap_node(scroll_to("nginx-proxy"))       # running: selected too, but only stopped guests start
    find("2 selected", timeout=10)
    tap("Start")                             # top bar action
    find("Start these guests?", timeout=10)
    screenshot("11g-bulk")
    tap("Start", pick="lowest")              # the dialog's button
    # The selection clears when every task has finished. The summary snackbar can come and go
    # between two UI dumps, so check the outcome on the list instead.
    wait_until(lambda: not visible("2 selected"), "the bulk start to finish", timeout=60)
    wait_until(lambda: row_shows("backup-client", "Running"), "backup-client to show as running", timeout=30)


@step("Clone the template, migrate the clone to another node, then delete it")
def clone_migrate():
    tap_node(scroll_to("ubuntu-2404-template"))
    tap_node(scroll_to("Clone"))
    find("Linked clone", timeout=30)       # templates clone linked by default
    tap_node(scroll_to("Create clone"))
    find("Open it", timeout=60)            # the clone task finished
    screenshot("11h-cloned")
    wait_for_snackbar_to_close()           # it covers the end of the form, where the button is
    tap_node(scroll_to("Open it"))
    find("ubuntu-2404-template-clone", timeout=30)
    tap_node(scroll_to("Migrate"))
    find("Offline migration", timeout=30)  # the clone is stopped; pve2 is preselected
    tap_node(scroll_to("Start migration"))
    tap("Start migration", pick="lowest")  # the confirmation dialog's button
    find("· pve2 ·", timeout=60)           # the guest screen again, now on pve2
    screenshot("11i-migrated")
    # Delete the clone again: type its ID, then the guest screen closes by itself.
    tap_node(scroll_to("Delete VM"))
    title = find("Delete VM ", timeout=10)  # "Delete VM 106 (ubuntu-2404-template-clone)?"
    vmid = re.search(r"Delete VM (\d+)", " ".join(labels(title))).group(1)
    fill(f"Type “{vmid}” to confirm", vmid, fallback_index=0)
    tap("Delete", pick="lowest")           # the dialog's button
    find("Open it", timeout=60)            # back on the clone screen
    screenshot("11j-clone-deleted")
    back()                                 # to the template
    scroll_to("Create new guests from this template")
    back()


@step("Tasks tab")
def tasks():
    tap("Tasks")
    find("VM destroy", timeout=30)         # the newest task, from the previous step
    find("VM migrate", timeout=10)
    screenshot("12-tasks")


@step("Create a VM on a node with the suggested settings, then open it")
def create_vm():
    tap("Resources")
    tap_node(scroll_to("pve1", exact=True))  # the node, not a guest row that mentions it
    tap_node(scroll_to("Create VM"))
    find("Installation disc", timeout=30)  # the form has loaded: next free ID, first storage, 32 GiB
    scroll_to("Start after creating")      # just above the button at the end of the form
    tap("Create VM", pick="lowest")        # the button, not the title
    find("Open it", timeout=60)            # the create task has finished
    screenshot("12b-created")
    wait_for_snackbar_to_close()
    tap_node(scroll_to("Open it"))
    find("Start", timeout=30)              # the new VM's screen; it is stopped
    back()                                 # to the node
    back()                                 # to Resources


def notifications():
    """Posted notifications as text (--noredact shows titles instead of their lengths)."""
    return adb("shell", "dumpsys", "notification", "--noredact", check=False)


@step("Background alerts through an anprox:// link: switch on, check now, a notification about the offline node")
def alerts():
    if int(adb("shell", "getprop", "ro.build.version.sdk").strip() or 0) >= 33:
        # Android 13+ would ask with a system dialog; the tour grants it up front instead.
        adb("shell", "pm", "grant", PKG, "android.permission.POST_NOTIFICATIONS", check=False)
    # Opened the way notifications and shortcuts do: an anprox:// link for the main activity.
    adb("shell", "am", "start", "-n", f"{PKG}/{ACTIVITY}", "-a", "android.intent.action.VIEW", "-d", "anprox://alerts")
    tap_node(scroll_to("Background alerts"))  # the switch at the top
    tap_node(scroll_to("Check now"))          # the last card, below the settings
    # Both servers answer; the mock's offline node pve3 and two full storages are reported.
    wait_until(lambda: visible("Servers checked: 2"), "the alert check to finish", timeout=90)
    screenshot("13-alerts")
    wait_until(lambda: "Node pve3 is offline" in notifications(), "the offline-node notification", timeout=30)
    back()


@step("Datacenter: backup jobs, add one with a preset schedule, run one now")
def backup_jobs():
    tap("Overview")
    tap_node(scroll_to("Datacenter", exact=True))   # the button on the cluster's card
    tap_node(scroll_to("Backup jobs"))
    find("Every day at 21:00", timeout=30)         # the mock's nightly job
    tap_node(scroll_to("Add backup job"))
    find("Next runs:", timeout=30)                 # Proxmox's reading of the default schedule
    tap("Weekdays at 21:00")
    tap_node(scroll_to("Create job"))
    find("Add backup job", timeout=30)             # back on the list
    find("Weekdays at 21:00", timeout=30)
    screenshot("13c-backup-jobs")
    tap_node(scroll_to("Run now"))                 # the nightly job's play button
    find("Run this backup job now?", timeout=10)
    tap("Run now", pick="lowest")                  # the dialog's button
    find("Backup job finished", timeout=90)        # one backup task per online node
    back()
    back()


@step("Datacenter: storage, the node downloads an ISO image from a URL and a template from the catalogue")
def storage():
    tap("Overview")
    tap_node(scroll_to("Datacenter", exact=True))
    tap_node(scroll_to("Storage", exact=True))
    find("nas-nfs", timeout=30)
    tap_node(scroll_to("pve1", exact=True))     # the local storage on pve1
    find("debian-12.7.0-amd64-netinst.iso", timeout=30)
    tap("From URL")
    find("Download from URL", timeout=10)
    url = "https://example.com/images/tiny-test.iso"
    fill("URL", url, fallback_index=0)
    # On Android 14 the URL has twice lost its last letter a second or two after it was typed
    # (and seen complete). Then the dialog refuses the file name and stays open: put the letter
    # back and try again, saying so in the log.
    for attempt in range(3):
        if attempt:
            print(f"    the download dialog stayed open (attempt {attempt}); fixing the URL", flush=True)
            fix_field(url)
        # The file name comes from the URL the app received, so it shows the whole URL arrived.
        wait_until(lambda: bool(matches(dump(), "tiny-test.iso", exact=True)), "the file name to be filled in", timeout=15)
        tap("Download", pick="lowest")          # the dialog's button
        try:
            wait_until(lambda: not visible("Download from URL"), "the download dialog to close", timeout=10)
            break
        except AssertionError:
            if attempt == 2:
                raise
    find("tiny-test.iso", timeout=60)           # listed once the node's download task has finished
    screenshot("13d-storage")
    scroll_to("Usage history")
    scroll_to("Year")                           # the timeframe picker under the header
    wait_until(lambda: any(v.startswith("Used ") and not v.endswith("—")
                           for n in dump().iter("node") for v in labels(n)),
               "the storage usage chart to show values", timeout=30)
    screenshot("13e-storage-usage")
    tap_node(scroll_to("CT templates", exact=True))   # the content type chip
    tap_node(scroll_to("Template catalogue", exact=True))
    find("On this storage", timeout=30)         # the distributions the mock already has
    tap_node(scroll_to("Download to this storage"))   # the first one left: Proxmox Mail Gateway
    find("Proxmox Mail Gateway 8.1 downloaded", timeout=60)
    screenshot("13f-template-catalogue")
    back()                                      # to the storage
    back()
    back()
    back()


@step("Datacenter: add an NFS storage, finding its exports on the server")
def storage_setup():
    tap("Overview")
    tap_node(scroll_to("Datacenter", exact=True))
    tap_node(scroll_to("Storage", exact=True))
    tap("Add storage")
    tap("NFS")
    fill("ID", "media", fallback_index=0)
    fill("Server", "10.0.0.50", fallback_index=1)
    tap_node(scroll_to("Find exports"))
    tap_node(scroll_to("/export/media"))          # the mock NAS's second export
    find("/export/media", timeout=10)
    screenshot("13d-storage-editor")
    tap_node(scroll_to("Create storage"))
    wait_until(lambda: not visible("Create storage"), "the editor to close", timeout=30)
    tap_node(scroll_to("Edit media", exact=True))  # listed; open it to check it saved
    find("/export/media", timeout=30)
    back()
    back()
    back()


@step("Datacenter: firewall, add a rule, open a security group and the IP sets, switch the firewall on")
def firewall():
    tap("Overview")
    tap_node(scroll_to("Datacenter", exact=True))
    tap_node(scroll_to("Firewall", exact=True))
    find("Admin network", timeout=30)              # the mock's SSH rule
    tap("Add rule")
    find("Destination port", timeout=10)
    tap("tcp")
    scroll_to("Destination port")
    fill("Destination port", "9100", fallback_index=2)
    tap("Add", pick="lowest")                      # the dialog's button
    wait_until(lambda: not visible("Destination port"), "the rule dialog to close", timeout=10)
    find("port 9100", timeout=30)                  # the new rule, first in the list
    screenshot("13e-firewall")
    tap_node(scroll_to("Security groups", exact=True))
    tap_node(scroll_to("webserver", exact=True))
    find("HTTPS", timeout=30)                      # the group's own rules
    back()
    back()
    tap_node(scroll_to("IP sets", exact=True))
    find("203.0.113.0/24", timeout=30)             # the mock's blocklist
    back()
    tap_node(scroll_to("Options", exact=True))
    tap_node(scroll_to("Firewall on"))
    tap_node(scroll_to("Save", exact=True))
    find("Firewall options saved", timeout=20)
    back()
    back()


@step("Datacenter: high availability, the manager's status, ignore a managed guest")
def high_availability():
    tap("Overview")
    tap_node(scroll_to("Datacenter", exact=True))
    tap_node(scroll_to("High availability", exact=True))
    find("Manager: pve1", timeout=30)
    tap_node(scroll_to("100 · web"))               # managed by HA in the mock, group prefer-pve1
    tap("Ignored")
    tap_node(scroll_to("Save", exact=True))
    find("HA settings saved", timeout=20)
    screenshot("13f-high-availability")
    back()
    back()


@step("Datacenter: replication, a synced and a failing job, add one")
def replication():
    tap("Overview")
    tap_node(scroll_to("Datacenter", exact=True))
    tap_node(scroll_to("Replication", exact=True))
    find("103 · k8s-master → pve1", timeout=30)    # in sync in the mock
    find("Failed (3)", timeout=10)                 # nginx-proxy to the offline pve3
    tap("Add replication job")
    find("Copy to", timeout=10)
    tap("Add", pick="lowest")                      # the dialog's button: web (on pve1) to pve2
    find("100 · web → pve2", timeout=30)
    screenshot("13g-replication")
    back()
    back()


@step("Datacenter: resource pools, add a guest to a pool")
def pools():
    tap("Overview")
    tap_node(scroll_to("Datacenter", exact=True))
    tap_node(scroll_to("Resource pools", exact=True))
    find("Production", timeout=30)                 # the prod pool's comment
    find("Guests: 3 · Storages: 1", timeout=10)
    tap("lab")                                    # exact matches win
    find("104 · lab-vm", timeout=30)               # its only guest
    tap("Add guests")
    tap("103 · k8s-master")
    tap("Add (1)")
    wait_until(lambda: not visible("Add (1)"), "the dialog to close", timeout=10)
    find("103 · k8s-master", timeout=30)           # now in the pool too
    screenshot("13h-pool")
    back()
    back()
    back()


@step("Datacenter: permissions - a user in a group, an API token shown once, a role for a group, a sync preview")
def permissions():
    tap("Overview")
    tap_node(scroll_to("Datacenter", exact=True))
    tap_node(scroll_to("Accounts, passwords, API tokens and second factors"))   # Users
    find("alice@corp", timeout=30)
    tap("Add user")
    fill("User name", "dave", fallback_index=0)
    fill_password(0, "dave-pass-1")
    fill_password(1, "dave-pass-1")
    tap_node(scroll_to("ops", exact=True))         # its only group
    tap_node(scroll_to("Create user", exact=True))
    tap_node(scroll_to("dave@pve", exact=True))
    find("Change password", timeout=30)
    tap_node(scroll_to("Add API token", exact=True))
    fill("Token name", "ci", fallback_index=0)
    tap("Create", pick="lowest")
    find("API token created", timeout=30)
    find("dave@pve!ci", timeout=10)               # the secret is shown once, under it
    tap("I've saved it")
    find("Own permissions", timeout=30)           # the new token, with separated privileges
    back()                                        # to Users
    back()                                        # to Datacenter
    tap_node(scroll_to("Who has which role, and where"))
    find("/pool/prod", timeout=30)                # given to an API token in the mock
    tap("Add permission")
    tap("/storage")
    tap("Group")
    tap("auditors")
    tap("Operator")                               # the mock's custom role
    tap("Add", pick="lowest")
    find("Operator given to auditors on /storage", timeout=30)
    screenshot("13i-permissions")
    back()
    tap_node(scroll_to("Where users sign in from"))   # Realms
    find("Active Directory", timeout=30)          # corp
    tap("Sync")
    tap("Preview", pick="lowest")                 # a dry run is the default
    tap("View log", timeout=20)
    find("adding user 'bob@corp'", timeout=30)
    back()                                        # to Realms
    back()                                        # to Datacenter
    back()


@step("Datacenter: Ceph - health with pve3 down, the noout flag, an OSD that is out, a new pool")
def ceph():
    tap("Overview")
    tap_node(scroll_to("Datacenter", exact=True))
    tap_node(scroll_to("Health, OSDs, pools, monitors, CephFS and flags"))   # Ceph
    find("2 osds down", timeout=30)               # pve3 is offline in the mock
    screenshot("13j-ceph")
    tap_node(scroll_to("OSDs will not automatically be marked out"))    # the noout switch
    find("noout set", timeout=20)
    tap_node(scroll_to("The disks that store the data, by node"))        # OSDs
    tap_node(scroll_to("osd.5"))                  # down and out in the mock
    find("Mark in", timeout=10)
    tap("Close")
    back()
    tap_node(scroll_to("Replication, placement groups and usage"))       # Pools
    find("vm-pool", timeout=30)
    tap("Create pool")
    fill("Pool name", "fast", fallback_index=0)
    tap("Create", pick="lowest")
    find("Pool fast created", timeout=60)
    back()                                        # to Ceph
    back()                                        # to Datacenter
    back()


@step("Datacenter: SDN - a VNet waiting to be applied, a subnet for it, apply on the nodes; the cluster log, datacenter options")
def sdn():
    tap("Overview")
    tap_node(scroll_to("Datacenter", exact=True))
    tap_node(scroll_to("Software-defined networks: zones, VNets, subnets, IPAM"))   # SDN
    find("1 change not applied yet", timeout=30)  # the mock's new VNet iot
    tap_node(scroll_to("IoT · Zone vlans"))
    find("No subnets yet", timeout=30)
    tap("Add subnet")
    fill("Subnet (CIDR)", "10.40.0.0/24", fallback_index=0)
    fill("Gateway", "10.40.0.1", fallback_index=1)
    tap("Create", pick="lowest")
    find("10.40.0.0/24", timeout=30)
    screenshot("13k-sdn-vnet")
    back()                                        # to SDN
    tap_node(scroll_to("Apply", exact=True))      # on the pending card
    tap("Apply", pick="lowest")                   # the confirmation
    find("SDN changes applied", timeout=60)
    back()                                        # to Datacenter
    tap_node(scroll_to("Cluster log", exact=True))
    find(":reloadnetworkall:", timeout=30)        # newest at the bottom: the SDN apply task's end
    screenshot("13l-cluster-log")
    back()                                        # to Datacenter
    tap_node(scroll_to("Datacenter options", exact=True))
    find("Console viewer", timeout=30)
    tap_node(scroll_to("xterm.js", exact=True))   # the mock's datacenter uses noVNC
    wait_until(lambda: not visible("No unsaved changes."), "the form to become dirty", timeout=15)
    tap_node(scroll_to("Save", exact=True))
    # Saved and reloaded: clean again (the snackbar is short).
    wait_until(lambda: visible("No unsaved changes."), "the options to be saved", timeout=30)
    screenshot("13m-datacenter-options")
    back()                                        # to Datacenter
    back()


@step("Backup Server: a datastore's backups, a container backup's files, a prune preview, run a sync job")
def backup_server():
    tap("Overview")
    tap_node(scroll_to("Manage Backup Server"))
    tap_node(scroll_to("Primary ZFS mirror"))     # store1
    tap_node(scroll_to("ct/200"))                 # pihole's backups
    find("Verified", timeout=30)
    tap_node(scroll_to("pihole"))                 # the newest snapshot (its notes)
    tap("Browse files")
    tap_node(scroll_to("root.pxar.didx"))
    tap_node(scroll_to("etc"))
    find("hosts", timeout=30)
    screenshot("13l-file-restore")
    back()                                        # up to root.pxar.didx
    back()                                        # up to the top
    back()                                        # to the snapshots
    tap("Prune")
    find("Keeps 2, removes 0", timeout=30)        # keep the last 3 of 2
    tap("Cancel")
    back()                                        # to the datastore
    back()                                        # to the server
    tap_node(scroll_to("Sync, verify and prune jobs"))
    tap_node(scroll_to("s-offsite"))              # failed last time in the mock
    tap("Run now")
    find("s-offsite finished", timeout=60)
    back()
    back()


@step("Node administration: updates, repositories, a service, a pending network change, the system log, start all, the task history")
def node_admin():
    tap("Resources")
    tap_node(scroll_to("pve1", exact=True))      # the node
    tap_node(scroll_to("Updates", exact=True))
    find("pve-manager", timeout=30)              # upgradable in the mock
    screenshot("15-updates")
    tap("Repositories")
    find("The enterprise repository is enabled", timeout=30)
    tap_node(scroll_to("Add No-Subscription", exact=True))
    wait_until(lambda: not visible("Add No-Subscription"), "the repository to be added", timeout=30)
    back()                                       # to Updates
    back()                                       # to the node
    tap_node(scroll_to("Services", exact=True))
    tap_node(scroll_to("ksmtuned", exact=True))  # stopped in the mock
    tap("Start", pick="lowest")
    wait_until(lambda: row_shows("ksmtuned", "Running"), "ksmtuned to run", timeout=60)
    back()
    tap_node(scroll_to("Bridges, bonds and VLANs"))  # Network (a chart is called Network too)
    find("vmbr0", timeout=30)
    tap("Bridge")                                # new bridge, named vmbr2
    tap("Save", pick="lowest")
    find("Changes not applied yet", timeout=30)
    tap("Revert")
    tap("Revert", pick="lowest")                 # the confirmation
    wait_until(lambda: not visible("Changes not applied yet"), "the changes to be reverted", timeout=30)
    back()
    tap_node(scroll_to("System log", exact=True))
    find("pvestatd", timeout=30)
    back()
    tap_node(scroll_to("Start all", exact=True))  # the node's stopped guests, in start order
    find("Start the guests on pve1?", timeout=10)
    tap("Start all", pick="lowest")              # the dialog's button
    find("Start all guests finished", timeout=60)
    screenshot("15b-start-all")
    tap_node(scroll_to("Task history", exact=True))
    find("Start all guests", timeout=30)         # the newest task of pve1
    tap("Failed")
    wait_until(lambda: not visible("Start all guests"), "the failed tasks only", timeout=30)
    back()                                       # to the node
    back()                                       # to Resources


def replace_field(label, value, fallback_index=None, attempts=3):
    """
    Like [fill], for a field that already holds text: deletes it first. The tap can leave the
    cursor anywhere and MOVE_END doesn't always land (CI once kept the last "1" of 10.0.0.11 and
    connected to 10.0.2.21), so it deletes backwards from the end and forwards from the start, and
    starts over until the field reads exactly [value].
    """
    for attempt in range(attempts):
        root = dump()
        found = matches(root, label, exact=True)
        fields = [n for n in root.iter("node") if n.get("class") == "android.widget.EditText"]
        node = found[0] if found else (fields[fallback_index] if fallback_index is not None and len(fields) > fallback_index else None)
        if node is None:
            save_dump(root, f"field-{slug(label)}")
            raise AssertionError(f"text field '{label}' not found")
        tap_node(node)
        adb("shell", "input", "keyevent", "KEYCODE_MOVE_END", *(["KEYCODE_DEL"] * 40))
        adb("shell", "input", "keyevent", "KEYCODE_MOVE_HOME", *(["KEYCODE_FORWARD_DEL"] * 40))
        adb("shell", "input", "text", value)
        time.sleep(0.6)
        complete_typing(label, value)
        texts = [n.get("text") or "" for n in dump().iter("node") if n.get("class") == "android.widget.EditText"]
        if value in texts:
            return
        print(f"    '{label}' reads {texts} instead of {value!r}; clearing it again", flush=True)
    raise AssertionError(f"couldn't get '{label}' to read {value!r}")


@step("SSH: a key made on the phone, password sign-in with the host key check, then the key")
def ssh():
    tap("More")
    tap("Settings")
    tap_node(scroll_to("SSH keys", exact=True))
    tap("New key")
    fill("Name", "Tour phone", fallback_index=0)
    tap("Create")
    find("Tour phone", timeout=30)
    screenshot("16-ssh-keys")
    back()                                        # Settings
    back()                                        # More
    tap("Resources")
    tap_node(scroll_to("pve1", exact=True))       # the node
    tap_node(scroll_to("SSH", exact=True))
    find("Host or IP address", timeout=30)
    # The mock's cluster says 10.0.0.11; the emulator reaches the mock's SSH server on the host.
    wait_until(lambda: not visible("Looking up the address"), "the address lookup", timeout=30)
    replace_field("Host or IP address", "10.0.2.2", fallback_index=0)
    replace_field("Port", "2222", fallback_index=2)
    tap("Password")                               # a key exists, so the form starts on SSH key
    fill("Password", "anprox", password=True)
    tap_node(scroll_to("Connect", exact=True))
    node = find_any(["Trust this server?", "Android System WebView"], timeout=45)
    if "Android System WebView" in " ".join(labels(node)):
        screenshot("16b-ssh-webview-too-old")
        back()
        back()
        return
    screenshot("16b-ssh-host-key")
    tap("Trust", timeout=10)
    find("Connected", timeout=45)
    tap("Add key")                                # the banner: signed in with a password
    tap_node(find("Tour phone", timeout=15))
    # The "… was added" snackbar is short; the banner going away says the same.
    wait_until(lambda: not visible("Signed in with a password"), "the key to be added", timeout=30)
    screenshot("16c-ssh-connected")
    tap("Change connection")
    tap_node(scroll_to("Connect", exact=True))   # the form, now on the key just added
    find("Connected", timeout=45)                 # no host key question the second time
    leave_console()
    back()                                        # the node, back to Resources


@step("Light theme, the app lock without a screen lock, favourites on the dashboard")
def light_theme():
    tap("More")
    tap("Settings")
    tap("Light")
    time.sleep(1)
    screenshot("13-settings-light")
    # The emulator has no screen lock, so turning on the app lock explains why it can't.
    tap_node(scroll_to("App lock"))
    find_any(["PIN, pattern or password", "verify your identity"], timeout=15)
    screenshot("13b-app-lock-needs-screen-lock")
    back()
    tap("Overview")
    find("homelab", timeout=30)
    scroll_to("k8s-master")                # pinned in the charts step: the Favourites card
    time.sleep(2)
    screenshot("14-dashboard-light")


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
        for s in (launch, add_pve, add_pbs, resources, performance, agent, guest_start, snapshots, backups,
                  options, hardware, console, vm_console, cloud_init, bulk, clone_migrate, tasks, create_vm, alerts,
                  backup_jobs, storage, storage_setup, firewall, high_availability, replication, pools, permissions, ceph, sdn, backup_server, node_admin, ssh,
                  light_theme):
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
