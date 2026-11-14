#!/usr/bin/env python3
"""Safely repair the known __main__ guard typo in the IRBIS Termux worker."""
from pathlib import Path
import py_compile
import shutil
import sys
import time

target = Path.home() / "osiris-bridge" / "worker.py"
if not target.is_file():
    print("ERROR: worker.py not found:", target)
    sys.exit(2)

try:
    original = target.read_text(encoding="utf-8")
except Exception as exc:
    print("ERROR: cannot read worker.py:", type(exc).__name__, str(exc))
    sys.exit(2)

lines = original.splitlines(keepends=True)
matches = [i for i, line in enumerate(lines) if line.strip() in ('if name == "main":', "if name == '__main__':", 'if __name__ == "main":')]
if not matches:
    if any(line.strip() == 'if __name__ == "__main__":' for line in lines):
        print("Main guard is already correct.")
        try:
            py_compile.compile(str(target), doraise=True)
            print("Syntax check: OK")
            sys.exit(0)
        except py_compile.PyCompileError as exc:
            print("SYNTAX ERROR:", exc)
            sys.exit(3)
    print("ERROR: expected broken main guard was not found; file left unchanged.")
    sys.exit(4)

if len(matches) != 1:
    print("ERROR: multiple candidate main guards found; file left unchanged.")
    sys.exit(4)

idx = matches[0]
indent = lines[idx][:len(lines[idx]) - len(lines[idx].lstrip())]
lines[idx] = indent + 'if __name__ == "__main__":\n'
updated = "".join(lines)

backup = target.with_name("worker.py.backup-" + time.strftime("%Y%m%d-%H%M%S"))
shutil.copy2(target, backup)
target.write_text(updated, encoding="utf-8")
try:
    py_compile.compile(str(target), doraise=True)
except py_compile.PyCompileError as exc:
    target.write_text(original, encoding="utf-8")
    print("SYNTAX ERROR after repair; original restored. Backup:", backup)
    print(exc)
    sys.exit(3)

print("Repaired:", target)
print("Backup:", backup)
print("Syntax check: OK")
