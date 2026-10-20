# -*- coding: utf-8 -*-
"""Compile one source tree against one version's classpath.

Usage: build.py <src-dir> <cp-short-file> <out-dir> [--release N]

A separate script from javac.py because the ports need a different classpath
and output directory per target, and keeping the 26.2 defaults out of the way
means a broken port can never silently overwrite the known-good build.
"""
import io
import os
import subprocess
import sys

JDK = r"C:\Program Files\Java\jdk-25.0.2"
JAVAC = os.path.join(JDK, "bin", "javac.exe")


def classpath(short):
    with io.open(short, "r", encoding="utf-8") as f:
        cp = f.read().strip()
    if not cp.endswith("*"):
        return cp
    # javac does not expand '*'; the directory's jars must be listed.
    parts = cp.split(";")
    out = []
    for part in parts:
        if part.endswith("*"):
            root = os.path.dirname(part)
            if not os.path.isdir(root):
                raise SystemExit("no such link dir: " + root)
            out.extend(sorted(
                os.path.join(root, n)
                for n in os.listdir(root)
                if n.lower().endswith(".jar")
            ))
        elif part:
            out.append(part)
    return os.pathsep.join(out)


def sources(src):
    out = []
    for base, _dirs, files in os.walk(src):
        for n in files:
            if n.endswith(".java"):
                out.append(os.path.join(base, n))
    return sorted(out)


def main():
    if len(sys.argv) < 4:
        raise SystemExit(__doc__)
    src, short, outdir = sys.argv[1], sys.argv[2], sys.argv[3]
    release = "25"
    if "--release" in sys.argv:
        release = sys.argv[sys.argv.index("--release") + 1]

    files = sources(src)
    if not files:
        raise SystemExit("no sources under " + src)
    os.makedirs(outdir, exist_ok=True)
    cp = classpath(short)
    args = [
        JAVAC,
        "-encoding", "UTF-8",
        "-proc:none",
        "-nowarn",
        "-Xlint:none",
        "--release", release,
        "-cp", cp,
        "-d", outdir,
    ] + files
    proc = subprocess.run(args, capture_output=True, text=True,
                          encoding="utf-8", errors="replace")
    if proc.stdout:
        sys.stdout.buffer.write(proc.stdout.encode("utf-8", "replace"))
    if proc.stderr:
        sys.stderr.buffer.write(proc.stderr.encode("utf-8", "replace"))
    print("javac exit %d, sources %d, cp entries %d"
          % (proc.returncode, len(files), len(cp.split(os.pathsep))))
    return proc.returncode


if __name__ == "__main__":
    sys.exit(main())
