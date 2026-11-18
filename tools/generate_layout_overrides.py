"""Generate K3 touch-layer override files from FlorisBoard alpha02 layout assets.

The personal keyboard uses the newer keyboard3 (K3) XML engine. This script converts
the older JSON layout definitions shipped with FlorisBoard alpha02 into small K3
fragments which only declare a single touch layer plus the keys it needs. The
loader merges those fragments into the base QWERTY/QWERTZ layout at runtime.
"""

from __future__ import annotations

import html
import json
import os
from pathlib import Path

LEGACY = Path(
    os.environ.get(
        "FLORIS_LEGACY_LAYOUTS",
        r"C:\Users\Naveen\AppData\Local\Temp\opencode\florisbeta\out\assets"
        r"\ime\keyboard\org.florisboard.layouts\layouts",
    )
)
OUT = Path(
    os.environ.get(
        "FLORIS_K3_LAYOUT_OUT",
        Path(__file__).resolve().parents[1]
        / "app/src/main/assets/extensions/org.florisboard.k3.foundation/layouts",
    )
)

SPECIAL_ACTIONS = {
    -7: ("@k3:action/backspace", False),
    10: ("@k3:action/enter", False),
    32: (r"\u{0020}", False),
    -201: (None, False),  # layerId base
    -202: (None, False),  # layerId symbol-1
    -206: (None, False),  # layerId telpad
    -207: (None, False),  # layerId telpad2
}
LAYER_ACTIONS = {
    -201: "base",
    -202: "symbol-1",
    -206: "telpad",
    -207: "telpad2",
}
CURRENCY_CODES = {code: f"currency-{-code - 800}" for code in range(-806, -800)}


def k3_text(value: str) -> str:
    out = []
    for ch in value:
        if ch in {'"', "\\"} or ord(ch) < 32:
            out.append(f"\\u{{{ord(ch):X}}}")
        else:
            out.append(ch)
    return html.escape("".join(out), quote=True)


def choose(cell: dict) -> dict:
    if "$" in cell:
        return cell.get("ltr") or cell.get("rtl") or {}
    return cell


class FileBuilder:
    def __init__(self, name: str, layer_id: str):
        self.name = name
        self.layer_id = layer_id
        self.keys: list[str] = []
        self.rows: list[str] = []
        self.counter = 0

    def new_id(self, suffix: str = "k") -> str:
        self.counter += 1
        return f"ov-{self.name}-{suffix}{self.counter}"

    def spec(self, cell: dict, suffix: str = "k", stretch_all: bool = False) -> tuple[str, str | None]:
        """Return (key id, optional key definition)."""
        cell = choose(cell)
        code = cell.get("code")
        label = cell.get("label", "")

        if code in CURRENCY_CODES:
            return CURRENCY_CODES[code], None

        key_id = self.new_id(suffix)
        attrs = [f'id="{key_id}"']

        if code in LAYER_ACTIONS:
            attrs.append(f'layerId="{LAYER_ACTIONS[code]}"')
        elif code in SPECIAL_ACTIONS:
            output, _ = SPECIAL_ACTIONS[code]
            attrs.append(f'output="{output}"')
        else:
            attrs.append(f'output="{k3_text(label)}"')

        stretch = stretch_all or code in {-7, 10, 32, -201, -202, -206, -207}
        if stretch:
            attrs.append('stretch="true"')

        popup = cell.get("popup") or {}
        popup_ids: list[str] = []
        default_id: str | None = None
        main = popup.get("main")
        if main:
            pid, pdef = self.spec(main, "p")
            popup_ids.append(pid)
            default_id = pid
            if pdef:
                self.keys.append(pdef)
        for i, relevant in enumerate(popup.get("relevant") or []):
            pid, pdef = self.spec(relevant, f"r{i}")
            popup_ids.append(pid)
            if pdef:
                self.keys.append(pdef)
        if popup_ids:
            attrs.append(f'longPressKeyIds="{" ".join(popup_ids)}"')
            attrs.append(f'longPressDefaultKeyId="{default_id or popup_ids[0]}"')

        return key_id, "    <key " + " ".join(attrs) + " />"

    def add_row(self, cells: list[dict], suffix: str, stretch_all: bool = False) -> None:
        ids: list[str] = []
        for i, cell in enumerate(cells):
            key_id, definition = self.spec(cell, f"{suffix}{i}", stretch_all)
            if definition:
                self.keys.append(definition)
            ids.append(key_id)
        self.rows.append("        <row keys=\"" + " ".join(ids) + "\" />")

    def add_gap_row(self, count: int) -> None:
        self.rows.append("        <row keys=\"" + " ".join(["gap"] * count) + "\" />")

    def write(self) -> None:
        OUT.mkdir(parents=True, exist_ok=True)
        lines = [
            '<keyboard3 xmlns="https://schemas.unicode.org/cldr/45/keyboard3" conformsTo="45" locale="en">',
            f'    <info author="FlorisBoard contributors" name="{self.name}" layout="custom" indicator="FB" />',
            "    <keys>",
            *self.keys,
            "    </keys>",
            '    <layers formId="touch">',
            f'        <layer id="{self.layer_id}">',
            *self.rows,
            "        </layer>",
            "    </layers>",
            "</keyboard3>",
            "",
        ]
        (OUT / f"{self.name}.xml").write_text("\n".join(lines), encoding="utf-8")


def load(folder: str, file_id: str) -> list[list[dict]]:
    path = LEGACY / folder / f"{file_id}.json"
    return json.loads(path.read_text(encoding="utf-8-sig"))


def build_symbols(prefix: str, layer: str, file_id: str) -> None:
    data = load("symbols" if prefix == "symbols" else "symbols2", file_id)
    builder = FileBuilder(f"{prefix}_layout_{file_id}", layer)
    for row_index, row in enumerate(data):
        builder.add_row(row, f"r{row_index}")
    if layer == "symbol-1" and len(data) < 3:
        builder.add_gap_row(10)
    # Reuse existing navigation keys from the base layout.
    builder.rows.append(
        "        <row keys=\"base "
        + ("comma numeric space period enter" if layer == "symbol-1"
           else "open-angle numeric space close-angle enter")
        + "\" />"
    )
    builder.write()


def build_numeric(prefix: str, layer: str, folder: str, file_id: str) -> None:
    data = load(folder, file_id)
    builder = FileBuilder(f"{prefix}_layout_{file_id}", layer)
    stretch_all = layer in {"numpad", "numeric"}
    for row_index, row in enumerate(data):
        builder.add_row(row, f"r{row_index}", stretch_all)
    builder.write()


def build_phone(prefix: str, layer: str, file_id: str) -> None:
    data = load("phone" if prefix == "phone" else "phone2", file_id)
    builder = FileBuilder(f"{prefix}_layout_{file_id}", layer)
    for row_index, row in enumerate(data):
        builder.add_row(row, f"r{row_index}", True)
    builder.write()


def main() -> None:
    for file_id in [
        "armenian", "cjk", "eastern", "ipa", "neo2", "persian", "western",
        "western_additional_symbols", "western_samsung",
    ]:
        build_symbols("symbols", "symbol-1", file_id)
    for file_id in [
        "cjk", "eastern", "ipa", "persian", "western", "western_samsung",
    ]:
        build_symbols("symbols2", "symbol-2", file_id)
    for file_id in ["western_arabic", "western_arabic_pc"]:
        build_numeric("numeric", "numpad", "numeric", file_id)
        build_numeric("numeric_advanced", "numeric", "numericAdvanced", file_id)
    for file_id in [
        "bengali", "cjk", "czech", "devanagari", "eastern_arabic", "gujarati",
        "gurmukhi", "kannada", "malayalam", "neo2", "oriya", "persian", "tamil",
        "telugu", "thai", "warang_citi", "western_arabic",
    ]:
        build_numeric("numeric_row", "numrow", "numericRow", file_id)
    build_phone("phone", "telpad", "telpad")
    build_phone("phone2", "telpad2", "telpad")
    print("Generated", len(list(OUT.glob("*.xml"))), "layout override files")


if __name__ == "__main__":
    main()
