"""Read the Android version and decide whether a trusted main push should release."""
import argparse
import json
import os
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
VERSION_FILE = "app/build.gradle.kts"


def read_version(text):
    name = re.findall(r'^\s*versionName\s*=\s*"([0-9]+\.[0-9]+\.[0-9]+)"\s*$', text, re.M)
    code = re.findall(r"^\s*versionCode\s*=\s*([0-9]+)\s*$", text, re.M)
    if len(name) != 1 or len(code) != 1 or int(code[0]) < 1:
        raise ValueError("Expected one semantic versionName and positive versionCode")
    return name[0], int(code[0])


def release_notes(version, code):
    log = (ROOT / "VERSIONLOG.md").read_text(encoding="utf-8")
    section = re.search(rf"^## {re.escape(version)}\s+[^\n]*\n(.*?)(?=^## |\Z)", log, re.M | re.S)
    if not section:
        raise ValueError(f"VERSIONLOG.md has no entry for {version}")
    return (f"# Chinese Flashcard {version}\n\n"
            f"Android release APK · versionCode {code}\n\n"
            f"{section.group(1).strip()}\n\n"
            "本 Release 的 APK 由 GitHub Actions 编译、签名并校验；"
            "未在此流程中执行设备安装或运行验收。SHA256SUMS.txt 用于下载完整性校验。\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--notes", type=Path)
    args = parser.parse_args()
    version, code = read_version((ROOT / VERSION_FILE).read_text(encoding="utf-8"))
    if args.notes:
        args.notes.write_text(release_notes(version, code), encoding="utf-8")
        return

    event_name = os.environ.get("GITHUB_EVENT_NAME", "local")
    event_path = os.environ.get("GITHUB_EVENT_PATH")
    event = json.loads(Path(event_path).read_text(encoding="utf-8")) if event_path else {}
    is_main = os.environ.get("GITHUB_REF") == "refs/heads/main"
    publish = False
    if event_name == "push" and is_main:
        before = event.get("before", "")
        if not re.fullmatch(r"[0-9a-f]{40}", before) or before == "0" * 40:
            raise ValueError("Cannot compare versions without a previous main commit; use manual dispatch")
        previous = subprocess.run(
            ["git", "show", f"{before}:{VERSION_FILE}"], cwd=ROOT,
            check=True, text=True, capture_output=True,
        ).stdout
        old_version, old_code = read_version(previous)
        if (version, code) != (old_version, old_code):
            if (tuple(map(int, version.split("."))) <= tuple(map(int, old_version.split(".")))
                    or code <= old_code):
                raise ValueError("A release must increase both versionName and versionCode")
            publish = True
    elif event_name == "workflow_dispatch":
        requested = event.get("inputs", {}).get("publish_release", "false") in (True, "true")
        if requested and not is_main:
            raise ValueError("Manual publishing is allowed only from main")
        publish = requested

    # Require release notes before starting an expensive release build.
    if publish:
        release_notes(version, code)
    values = {"version": version, "code": str(code), "tag": f"v{version}",
              "publish": str(publish).lower()}
    output = os.environ.get("GITHUB_OUTPUT")
    if output:
        with Path(output).open("a", encoding="utf-8") as handle:
            handle.writelines(f"{key}={value}\n" for key, value in values.items())
    print(json.dumps(values))


if __name__ == "__main__":
    main()
