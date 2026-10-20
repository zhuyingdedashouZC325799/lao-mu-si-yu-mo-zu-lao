import os, sys, zipfile, json

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def add_tree(z, base, prefix=""):
    count = 0
    for dirpath, dirnames, filenames in os.walk(base):
        dirnames.sort()
        for fn in sorted(filenames):
            full = os.path.join(dirpath, fn)
            rel = os.path.relpath(full, base).replace(os.sep, "/")
            z.write(full, prefix + rel)
            count += 1
    return count


def build(build_dir, out_path):
    classes = os.path.join(ROOT, build_dir, "classes")
    res = os.path.join(ROOT, build_dir, "res")
    if not os.path.isdir(classes):
        raise SystemExit("missing classes: " + classes)
    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    if os.path.exists(out_path):
        os.remove(out_path)
    n = 0
    with zipfile.ZipFile(out_path, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        n += add_tree(z, classes, "")
        n += add_tree(z, res, "")
    print("packed", n, "entries ->", out_path)
    print("size %.1f MB" % (os.path.getsize(out_path) / 1048576.0))


if __name__ == "__main__":
    build_dir = sys.argv[1]
    out_path = sys.argv[2]
    build(build_dir, out_path)
