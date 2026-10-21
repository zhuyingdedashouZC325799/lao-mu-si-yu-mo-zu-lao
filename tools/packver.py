# -*- coding: utf-8 -*-
"""Package one already-compiled build directory into its release jar.

Usage: packver.py <build-dir> <mod-version>

Release jars carry the Chinese display name, and cmd.exe mangles Chinese on
the command line, so both the name and the version live in TARGETS instead of
argv.  The jar keeps an ASCII-safe copy next to it because the GitHub release
asset name and every download URL must stay ASCII.
"""
import os
import shutil
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import pack

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
NAME = u"\u8001\u7267\u7940\u4e0e\u9b54\u65cf\u4f6c"  # 老牧祀与魔族佬
ASCII = "lao-mu-si-yu-mo-zu-lao"

TARGETS = {
    "build262":      "1.0.0-fabric-26.2",
    "build261fab":   "1.0.0-fabric-26.1.2",
    "build1201":     "1.0.0-fabric-1.20.1",
    "build261neo":   "1.0.0-neoforge-26.1.2",
}


def main():
    if len(sys.argv) < 2:
        raise SystemExit(__doc__)
    build_dir = sys.argv[1]
    if build_dir not in TARGETS:
        raise SystemExit("unknown build dir %r" % build_dir)
    version = TARGETS[build_dir]

    out_dir = os.path.join(ROOT, "out")
    zh = os.path.join(out_dir, NAME + u"-" + version + u".jar")
    pack.build(build_dir, zh)

    ascii_path = os.path.join(out_dir, "%s-%s.jar" % (ASCII, version))
    shutil.copyfile(zh, ascii_path)
    print("release (zh)   :", zh)
    print("release (ascii):", ascii_path)
    print("bytes          :", os.path.getsize(ascii_path))
    return 0


if __name__ == "__main__":
    sys.exit(main())
