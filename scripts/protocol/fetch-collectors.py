"""Restore pinned Frida runtime dependencies; verify decompressed bytes before install."""
import hashlib
import json
import lzma
import os
from pathlib import Path
from urllib.request import urlopen

root = Path(__file__).resolve().parents[2]
for entry in json.loads(Path(__file__).with_name("collector-dependencies.json").read_text()):
    target = root / "app/src/main/jniLibs" / entry["abi"] / "libjpq_inject.so"
    if target.exists() and hashlib.sha256(target.read_bytes()).hexdigest() == entry["sha256"]:
        print(entry["abi"] + ": verified existing dependency")
        continue
    with urlopen(entry["url"], timeout=120) as response:
        data = lzma.decompress(response.read())
    if hashlib.sha256(data).hexdigest() != entry["sha256"]:
        raise RuntimeError("Checksum mismatch: " + entry["abi"])
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = target.with_suffix(".download")
    temporary.write_bytes(data)
    temporary.chmod(0o755)
    os.replace(temporary, target)
    print(entry["abi"] + ": restored and verified")
