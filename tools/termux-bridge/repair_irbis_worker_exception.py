#!/usr/bin/env python3
from pathlib import Path
import os
import shutil
import sys
import time
import py_compile

target = Path.home() / "osiris-bridge" / "worker.py"
if not target.is_file():
    raise SystemExit("ERROR: worker.py not found")

source = target.read_text(encoding="utf-8")
old = "type(e).name"
new = "type(e).__name__"

if old not in source:
    if new in source:
        print("OK: exception type formatter is already correct")
    else:
        raise SystemExit("ERROR: expected exception handler pattern not found; no changes made")
else:
    backup = target.with_name("worker.py.backup-" + time.strftime("%Y%m%d-%H%M%S"))
    shutil.copy2(target, backup)
    target.write_text(source.replace(old, new), encoding="utf-8")
    try:
        py_compile.compile(str(target), doraise=True)
    except Exception:
        shutil.copy2(backup, target)
        raise
    os.chmod(target, 0o700)
    print("FIXED: exception handler formatter")
    print("Backup:", backup)

py_compile.compile(str(target), doraise=True)
print("OK: Python syntax check passed")
