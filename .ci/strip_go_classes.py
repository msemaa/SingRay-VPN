"""Remove duplicated gomobile runtime classes (the `go/` package) from an AAR.

Both libbox.aar (sing-box) and libXray.aar (Xray-core) ship their own copy of
the gomobile runtime. Bundling both makes D8 abort with
"Type go.Seq is defined multiple times", so we strip the `go/` package from
the second AAR before it reaches the dexer.

Usage:
    python3 .ci/strip_go_classes.py app/libs/libXray.aar
"""

import io
import os
import sys
import zipfile


def _strip_jar(data: bytes) -> bytes:
    """Return classes.jar bytes with every `go/...` entry removed."""
    buf = io.BytesIO()
    with zipfile.ZipFile(io.BytesIO(data), "r") as jar_in, zipfile.ZipFile(
        buf, "w", compression=zipfile.ZIP_DEFLATED
    ) as jar_out:
        for entry in jar_in.infolist():
            if entry.filename.startswith("go/"):
                continue
            jar_out.writestr(entry, jar_in.read(entry.filename))
    return buf.getvalue()


def strip(aar_path: str) -> None:
    tmp_path = aar_path + ".clean"
    with zipfile.ZipFile(aar_path, "r") as aar_in, zipfile.ZipFile(
        tmp_path, "w", compression=zipfile.ZIP_DEFLATED
    ) as aar_out:
        for item in aar_in.infolist():
            data = aar_in.read(item.filename)
            if item.filename == "classes.jar":
                data = _strip_jar(data)
            aar_out.writestr(item, data)
    os.replace(tmp_path, aar_path)
    print("Stripped duplicate gomobile classes from " + aar_path)


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("usage: strip_go_classes.py <path-to.aar>")
    strip(sys.argv[1])
