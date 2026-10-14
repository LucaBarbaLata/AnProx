#!/usr/bin/env python3
"""
Builds the GitHub release notes for a version from CHANGELOG.md.

Usage: python3 scripts/release/notes.py <version> <signing-cert-sha256> > notes.md

Takes the CHANGELOG section headed "## [<version>]" (or "## [Unreleased]" as a fallback) and
adds install/verification instructions.
"""
import re
import sys


def changelog_section(version: str) -> str:
    text = open("CHANGELOG.md", encoding="utf-8").read()
    for heading in (re.escape(f"[{version}]"), r"\[Unreleased\]"):
        m = re.search(rf"^## {heading}.*?$\n(.*?)(?=^## |\Z)", text, re.S | re.M)
        if m:
            return m.group(1).strip()
    return "_No changelog entry found._"


def main() -> None:
    version, cert = sys.argv[1], sys.argv[2]
    pre = "-" in version
    apk = f"AnProx-{version}.apk"
    print(f"""{"> **Beta release.** Expect rough edges and please [report bugs](https://github.com/LucaBarbaLata/AnProx/issues/new/choose). See the [beta testing guide](https://github.com/LucaBarbaLata/AnProx/blob/HEAD/docs/beta-testing.md) for what to test." if pre else ""}

{changelog_section(version)}

## Install

1. Download **`{apk}`** below and open it on your Android 8.0+ phone (allow "install unknown apps" for your browser or file manager when asked).
2. To get updates automatically, add `https://github.com/LucaBarbaLata/AnProx` to [Obtainium](https://github.com/ImranR98/Obtainium){" and enable *Include prereleases*" if pre else ""}.

## Verify

- SHA-256 of the APK: see `{apk}.sha256`. Check it with `sha256sum -c {apk}.sha256`.
- Signing certificate SHA-256: `{cert}`. It must be the same for every AnProx release; [AppVerifier](https://github.com/soupslurpr/AppVerifier) can check it on the phone.

AnProx is independent and not affiliated with Proxmox Server Solutions GmbH. Free software under the GPL-3.0.
""".strip())


if __name__ == "__main__":
    main()
