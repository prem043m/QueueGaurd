#!/usr/bin/env python3
"""
Converts REPORT.md into a submission-ready REPORT.docx (python-docx).
Handles: headings, paragraphs, bullet/numbered lists, pipe tables,
code blocks (monospace), blockquotes and inline bold/code emphasis.
"""
import re
from pathlib import Path

from docx import Document
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.shared import Pt, RGBColor, Inches
from docx.oxml.ns import qn
from docx.oxml import OxmlElement

SRC = Path(__file__).parent / "REPORT.md"
DST = Path(__file__).parent / "REPORT.docx"


def set_cell_shading(cell, hex_color):
    tc_pr = cell._tc.get_or_add_tcPr()
    shd = OxmlElement("w:shd")
    shd.set(qn("w:val"), "clear")
    shd.set(qn("w:fill"), hex_color)
    tc_pr.append(shd)


def add_inline(paragraph, text, base_bold=False):
    """Handles **bold**, *italic* and `code` inline markers."""
    pattern = re.compile(r"(\*\*.+?\*\*|\*[^*]+?\*|`[^`]+?`)")
    pos = 0
    for match in pattern.finditer(text):
        if match.start() > pos:
            run = paragraph.add_run(text[pos:match.start()])
            run.bold = base_bold
        token = match.group(0)
        if token.startswith("**"):
            run = paragraph.add_run(token[2:-2])
            run.bold = True
        elif token.startswith("`"):
            run = paragraph.add_run(token[1:-1])
            run.font.name = "Consolas"
            run.font.size = Pt(9)
            run.font.color.rgb = RGBColor(0x33, 0x33, 0x33)
        else:
            run = paragraph.add_run(token[1:-1])
            run.italic = True
        pos = match.end()
    if pos < len(text):
        run = paragraph.add_run(text[pos:])
        run.bold = base_bold


def add_table(doc, rows):
    header = [c.strip() for c in rows[0].strip().strip("|").split("|")]
    body = []
    for row in rows[2:]:
        body.append([c.strip() for c in row.strip().strip("|").split("|")])
    table = doc.add_table(rows=1 + len(body), cols=len(header))
    table.style = "Table Grid"
    for j, h in enumerate(header):
        cell = table.rows[0].cells[j]
        cell.text = ""
        p = cell.paragraphs[0]
        run = p.add_run(re.sub(r"\*\*(.+?)\*\*", r"\1", h))
        run.bold = True
        run.font.size = Pt(9.5)
        set_cell_shading(cell, "D9E2F3")
    for i, row in enumerate(body):
        for j in range(len(header)):
            cell = table.rows[i + 1].cells[j]
            cell.text = ""
            p = cell.paragraphs[0]
            txt = row[j] if j < len(row) else ""
            add_inline(p, txt)
            for run in p.runs:
                run.font.size = Pt(9)
    doc.add_paragraph()


def main():
    lines = SRC.read_text(encoding="utf-8").splitlines()
    doc = Document()

    style = doc.styles["Normal"]
    style.font.name = "Calibri"
    style.font.size = Pt(11)

    i = 0
    first_h1 = True
    while i < len(lines):
        line = lines[i]
        stripped = line.strip()

        if stripped.startswith("```"):
            block = []
            i += 1
            while i < len(lines) and not lines[i].strip().startswith("```"):
                block.append(lines[i])
                i += 1
            i += 1
            for bl in block:
                p = doc.add_paragraph()
                p.paragraph_format.space_after = Pt(0)
                p.paragraph_format.space_before = Pt(0)
                run = p.add_run(bl if bl else " ")
                run.font.name = "Consolas"
                run.font.size = Pt(8.5)
            doc.add_paragraph()
            continue

        if stripped.startswith("|") and i + 1 < len(lines) and re.match(r"^\s*\|[\s:|-]+\|\s*$", lines[i + 1]):
            rows = []
            while i < len(lines) and lines[i].strip().startswith("|"):
                rows.append(lines[i])
                i += 1
            add_table(doc, rows)
            continue

        if stripped.startswith("# "):
            text = stripped[2:].strip()
            if first_h1:
                p = doc.add_paragraph()
                p.alignment = WD_ALIGN_PARAGRAPH.CENTER
                run = p.add_run(text)
                run.bold = True
                run.font.size = Pt(20)
                run.font.color.rgb = RGBColor(0x1F, 0x3B, 0x63)
                first_h1 = False
            else:
                doc.add_heading(text, level=1)
        elif stripped.startswith("## "):
            doc.add_heading(stripped[3:].strip(), level=1)
        elif stripped.startswith("### "):
            doc.add_heading(stripped[4:].strip(), level=2)
        elif stripped.startswith("#### "):
            doc.add_heading(stripped[5:].strip(), level=3)
        elif stripped == "---":
            pass
        elif stripped.startswith(">"):
            p = doc.add_paragraph()
            run = p.add_run(stripped.lstrip("> ").strip())
            run.italic = True
            run.font.color.rgb = RGBColor(0x55, 0x55, 0x55)
        elif re.match(r"^[-*] ", stripped):
            p = doc.add_paragraph(style="List Bullet")
            add_inline(p, stripped[2:].strip())
        elif re.match(r"^\d+\. ", stripped):
            p = doc.add_paragraph(style="List Number")
            add_inline(p, re.sub(r"^\d+\.\s+", "", stripped))
        elif stripped:
            p = doc.add_paragraph()
            add_inline(p, stripped)
        i += 1

    doc.save(DST)
    print(f"wrote {DST} ({DST.stat().st_size // 1024} KB)")


if __name__ == "__main__":
    main()
