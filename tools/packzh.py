# -*- coding: utf-8 -*-
"""Build the release jar under its Chinese display name.

cmd.exe hands argv to python in the OEM codepage (936 here), so a Chinese
path typed on the command line arrives mangled and pack.py ends up trying
to create a directory literally named '"out'. Keeping the literal inside
this UTF-8 source avoids the round trip entirely.
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import pack

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

BUILD = "build262"
NAME = u"\u8001\u7267\u7940\u4e0e\u9b54\u65cf\u4f6c"  # 老牧祀与魔族佬


def main():
    out = os.path.join(ROOT, "out", NAME + u"-1.0.0-fabric-26.2.jar")
    pack.build(BUILD, out)
    print("release:", out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
