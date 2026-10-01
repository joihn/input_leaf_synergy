#!/usr/bin/env python3
"""Register an Input Leaf client in a stopped Synergy 3.7 service (macOS).

Dry-run by default. Never reads or copies certificate private keys, and never
prints the settings database (which also contains the Synergy license).
"""
import argparse
import copy
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import time


def fingerprint(value):
    value = re.sub(r"[:\s]", "", value).lower()
    if not re.fullmatch(r"[0-9a-f]{64}", value):
        raise ValueError("Provide the complete SHA-256 fingerprint from Input Leaf Settings.")
    return value


def core_name(computer):
    name = re.sub(r"[^a-zA-Z0-9]", "", computer["name"]).lower()
    suffix = computer["id"][-8:]
    return f"{name}-{suffix}" if name else suffix


def overlaps(a, b):
    return (a["left"] < b["left"] + b["width"] and b["left"] < a["left"] + a["width"]
            and a["top"] < b["top"] + b["height"] and b["top"] < a["top"] + a["height"])


def prepare(database, local, name, client_fingerprint, side="below"):
    """Return updated copies; preserve unrelated computers, trust, and options."""
    if database.get("version", {}).get("schemaVersion") != 17 or local.get("version") != 8:
        raise ValueError("Unsupported Synergy settings schema; this helper targets Synergy 3.7 (17/8).")
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9 _.-]{0,47}", name):
        raise ValueError("Name must be 1–48 ASCII letters, digits, spaces, dots, underscores or hyphens.")
    client_fingerprint = fingerprint(client_fingerprint)
    updated, updated_local = copy.deepcopy(database), copy.deepcopy(local)
    data = updated["data"]
    if data.get("override", {}).get("shouldOverride"):
        raise ValueError("Custom config is enabled. Add the phone to that config manually first.")
    if not data.get("security", {}).get("encryption"):
        raise ValueError("Enable TLS encryption in Synergy before using this helper.")
    primary_id = data["mainID"]["id"]
    if primary_id != local["myId"]:
        raise ValueError("Run this helper on the primary Mac (choose it as primary in Synergy first).")
    computers = data["computers"]
    primary = next(c for c in computers if c["id"] == primary_id and not c.get("isRemoved"))
    # Stable across certificate regeneration. Never reuse an existing desktop identity.
    client_id = hashlib.sha256(("input-leaf:synergy3:" + name).encode()).hexdigest()
    existing = next((c for c in computers if c["id"] == client_id), None)
    if any(c["name"] == name and c["id"] != client_id for c in computers):
        raise ValueError("That display name already belongs to another computer.")
    if existing is None or existing.get("isRemoved"):
        bounds = primary["misc"]
        position = {key: bounds[key] for key in ("left", "top", "width", "height")}
        if side == "below": position["top"] += bounds["height"] + 1
        elif side == "above": position["top"] -= position["height"] + 1
        elif side == "right": position["left"] += bounds["width"] + 1
        elif side == "left": position["left"] -= position["width"] + 1
        else: raise ValueError("Unknown side")
        if any(overlaps(position, c["misc"]) for c in computers
               if not c.get("isRemoved") and c["id"] != client_id):
            raise ValueError("That position is occupied. Choose another --side or move screens in Synergy.")
        phone = {
            "id": client_id, "name": name, "hostname": name, "isRemoved": False,
            "dead_corners": {corner: "" for corner in ("top-left", "top-right", "bottom-left", "bottom-right")},
            "scroll_sensitivity": 0.5,
            "fix": {key: False for key in ("num_lock", "xtest", "capslock", "scr_lock", "sync_language", "invert_scroll", "prevent_sleep")},
            "hdk": {key: False for key in ("caps", "scr_lock", "num_lock")},
            "modifier": {key: key for key in ("Alt", "Meta", "Shift", "Super", "Ctrl")},
            # Synergy's layout editor understands Linux, not Android, as an OS label.
            "misc": {**position, "os": "linux", "connected": False, "logo": "", "customImage": None,
                     "screenCount": 1, "screens": {}, "tlsFingerprint": client_fingerprint},
        }
        if existing is not None: computers.remove(existing)
        computers.append(phone)
    else:
        phone = existing
        phone["misc"]["tlsFingerprint"] = client_fingerprint
    peers = updated_local["local_security"].setdefault("trusted_peers", [])
    if any(p["fingerprint"] == client_fingerprint and p["computerId"] != client_id for p in peers):
        raise ValueError("This fingerprint already belongs to a different computer; use the phone's own certificate.")
    matching = [p for p in peers if p["computerId"] == client_id]
    if len(matching) != 1 or matching[0]["fingerprint"] != client_fingerprint:
        peers[:] = [p for p in peers if p["computerId"] != client_id]
        peers.append({"fingerprint": client_fingerprint, "computerId": client_id, "name": name,
                      "trustedAt": int(time.time() * 1000)})
    if data != database["data"]:
        updated["version"]["syncVersion"] = max(int(time.time() * 1000), database["version"]["syncVersion"] + 1)
    return updated, updated_local, core_name(phone)


def require_stopped():
    # Pausing the core is insufficient: a running service can overwrite both files.
    result = subprocess.run(["ps", "-axo", "comm="], check=True, capture_output=True, text=True)
    if any(Path(line.strip()).name in ("synergy-service", "synergy-core") for line in result.stdout.splitlines()):
        raise ValueError("Quit Synergy and stop its background service before --apply; pausing sharing is insufficient.")


def atomic_write(path, contents):
    fd, temporary = tempfile.mkstemp(prefix=".input-leaf-", dir=path.parent)
    try:
        with os.fdopen(fd, "wb") as output:
            output.write(contents)
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary): os.unlink(temporary)


def apply_changes(directory, originals, updates):
    require_stopped()
    for name, original in originals.items():
        if (directory / name).read_bytes() != original:
            raise ValueError("Settings changed since the preview was read. Run the command again.")
    backup = Path(tempfile.mkdtemp(prefix="input-leaf-backup-", dir=directory))
    for name, original in originals.items():
        atomic_write(backup / name, original)
    try:
        for name, value in updates.items():
            atomic_write(directory / name, (json.dumps(value, indent=2) + "\n").encode())
    except BaseException:
        for name, original in originals.items():
            atomic_write(directory / name, original)
        raise
    return backup


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config-dir", type=Path, default=Path.home() / "Library/Preferences/Synergy")
    parser.add_argument("--name", default="android-phone", help="Display name for the phone in Synergy")
    parser.add_argument("--fingerprint", required=True, help="Full SHA-256 fingerprint shown by Input Leaf")
    parser.add_argument("--side", choices=("below", "above", "left", "right"), default="below")
    parser.add_argument("--apply", action="store_true", help="Write backed-up settings; requires a stopped Synergy service")
    args = parser.parse_args()
    try:
        originals = {name: (args.config_dir / name).read_bytes() for name in ("db.json", "local.json")}
        database, local = (json.loads(originals[name]) for name in ("db.json", "local.json"))
        updated, updated_local, screen = prepare(database, local, args.name, args.fingerprint, args.side)
        print(f"Input Leaf screen name: {screen}")
        print(f"Phone SHA-256: {fingerprint(args.fingerprint)}")
        primary = next(c for c in database["data"]["computers"] if c["id"] == local["myId"])
        print(f"Server SHA-256: {primary['misc'].get('tlsFingerprint', 'Check Synergy Security settings')}")
        if updated == database and updated_local == local:
            print("Already registered; no changes needed.")
        elif args.apply:
            backup = apply_changes(args.config_dir, originals, {"db.json": updated, "local.json": updated_local})
            print(f"Saved. Backup: {backup}")
            print("Start Synergy, set the screen name above in Input Leaf, then connect to this Mac on port 24800.")
        else:
            print("Preview only. After verifying the phone fingerprint, stop Synergy's service and rerun with --apply.")
    except (OSError, ValueError, KeyError, StopIteration, subprocess.SubprocessError) as error:
        parser.exit(1, f"Cannot register phone: {error}\n")


if __name__ == "__main__":
    main()
