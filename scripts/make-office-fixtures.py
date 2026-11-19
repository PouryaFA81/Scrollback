#!/usr/bin/env python3
"""Generates the small office fixtures used by OfficeTest (app/src/test/resources/office/).

- lo-*.docx / lo-*.xlsx / lo-*.pptx come from LibreOffice (realistic files with styles, numbering, rels).
- min-*.{docx,xlsx,pptx} are hand-written minimal packages covering edge cases (inline strings,
  sparse cells, booleans, slide images, speaker notes).
Run: scripts/make-office-fixtures.py   (needs `soffice` for the lo-* files)
"""
import os, shutil, subprocess, tempfile, zipfile, base64

OUT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "test", "resources", "office")
os.makedirs(OUT, exist_ok=True)
PNG = base64.b64decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==")
CT = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n'

def z(name, parts):
    with zipfile.ZipFile(os.path.join(OUT, name), "w", zipfile.ZIP_DEFLATED) as f:
        for k, v in parts.items():
            f.writestr(k, v)

W = 'xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"'
z("min.docx", {
    "[Content_Types].xml": CT + '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"/>',
    "word/document.xml": CT + f'''<w:document {W}><w:body>
<w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>Lesson plan</w:t></w:r></w:p>
<w:p><w:r><w:t xml:space="preserve">Plain and </w:t></w:r><w:r><w:rPr><w:b/></w:rPr><w:t>bold</w:t></w:r><w:r><w:rPr><w:i/><w:b w:val="0"/></w:rPr><w:t> italic</w:t></w:r></w:p>
<w:p/><w:p/>
<w:p><w:pPr><w:numPr><w:ilvl w:val="0"/><w:numId w:val="1"/></w:numPr></w:pPr><w:r><w:t>First bullet</w:t></w:r></w:p>
<w:p><w:pPr><w:numPr><w:ilvl w:val="1"/><w:numId w:val="1"/></w:numPr></w:pPr><w:r><w:t>Nested</w:t></w:r></w:p>
<w:p><w:pPr><w:numPr><w:ilvl w:val="0"/><w:numId w:val="2"/></w:numPr></w:pPr><w:r><w:t>Step one</w:t></w:r></w:p>
<w:tbl><w:tr><w:tc><w:p><w:r><w:t>Name</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>Score</w:t></w:r></w:p></w:tc></w:tr>
<w:tr><w:tc><w:p><w:r><w:t>Sara</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>19</w:t></w:r></w:p><w:p><w:r><w:t>(A)</w:t></w:r></w:p></w:tc></w:tr></w:tbl>
<w:p><w:pPr><w:pStyle w:val="Custom7"/></w:pPr><w:r><w:t>Localized heading</w:t></w:r></w:p>
<w:p/>
</w:body></w:document>''',
    "word/styles.xml": CT + f'<w:styles {W}><w:style w:styleId="Custom7"><w:name w:val="heading 2"/></w:style></w:styles>',
    "word/numbering.xml": CT + f'''<w:numbering {W}>
<w:abstractNum w:abstractNumId="10"><w:lvl w:ilvl="0"><w:numFmt w:val="bullet"/></w:lvl><w:lvl w:ilvl="1"><w:numFmt w:val="bullet"/></w:lvl></w:abstractNum>
<w:abstractNum w:abstractNumId="11"><w:lvl w:ilvl="0"><w:numFmt w:val="decimal"/></w:lvl></w:abstractNum>
<w:num w:numId="1"><w:abstractNumId w:val="10"/></w:num><w:num w:numId="2"><w:abstractNumId w:val="11"/></w:num>
</w:numbering>''',
})

S = 'xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"'
REL = 'xmlns="http://schemas.openxmlformats.org/package/2006/relationships"'
z("min.xlsx", {
    "xl/workbook.xml": CT + f'<workbook {S}><sheets><sheet name="Grades" sheetId="1" r:id="rId1"/><sheet name="Empty" sheetId="2" r:id="rId2"/></sheets></workbook>',
    "xl/_rels/workbook.xml.rels": CT + f'<Relationships {REL}><Relationship Id="rId1" Type="ws" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="ws" Target="/xl/worksheets/sheet2.xml"/></Relationships>',
    "xl/sharedStrings.xml": CT + f'<sst {S}><si><t>Name</t></si><si><r><t>Sco</t></r><r><t>re</t></r></si><si><t>Ali</t><rPh><t>IGNORED</t></rPh></si></sst>',
    "xl/worksheets/sheet1.xml": CT + f'''<worksheet {S}><sheetData>
<row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c><c r="D1" t="inlineStr"><is><t>Passed</t></is></c></row>
<row r="2"><c r="A2" t="s"><v>2</v></c><c r="B2"><v>18.0</v></c><c r="D2" t="b"><v>1</v></c></row>
<row r="4"><c r="B4"><v>2.5</v></c><c r="C4" t="str"><f>B4*2</f><v>5</v></c></row>
</sheetData></worksheet>''',
    "xl/worksheets/sheet2.xml": CT + f'<worksheet {S}><sheetData/></worksheet>',
})

P = 'xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"'
def slide(title, bullets, pic=False):
    sp = f'<p:sp><p:nvSpPr><p:cNvPr id="2" name="Title"/><p:cNvSpPr/><p:nvPr><p:ph type="title"/></p:nvPr></p:nvSpPr><p:txBody><a:p><a:r><a:t>{title}</a:t></a:r></a:p></p:txBody></p:sp>'
    sp += '<p:sp><p:nvSpPr><p:cNvPr id="3" name="Body"/><p:cNvSpPr/><p:nvPr><p:ph idx="1"/></p:nvPr></p:nvSpPr><p:txBody>' + "".join(f'<a:p><a:r><a:t>{b}</a:t></a:r></a:p>' for b in bullets) + '<a:p/></p:txBody></p:sp>'
    if pic:
        sp += '<p:pic><p:nvPicPr><p:cNvPr id="4" name="Picture"/><p:cNvPicPr/><p:nvPr/></p:nvPicPr><p:blipFill><a:blip r:embed="rId2"/></p:blipFill></p:pic>'
    return CT + f'<p:sld {P}><p:cSld><p:spTree>{sp}</p:spTree></p:cSld></p:sld>'
z("min.pptx", {
    "ppt/presentation.xml": CT + f'<p:presentation {P}><p:sldIdLst><p:sldId id="257" r:id="rId3"/><p:sldId id="256" r:id="rId2"/></p:sldIdLst></p:presentation>',
    "ppt/_rels/presentation.xml.rels": CT + f'<Relationships {REL}><Relationship Id="rId2" Type="slide" Target="slides/slide1.xml"/><Relationship Id="rId3" Type="slide" Target="slides/slide2.xml"/><Relationship Id="rId9" Type="hl" Target="https://example.com" TargetMode="External"/></Relationships>',
    "ppt/slides/slide1.xml": slide("Second in file order", ["Body A"], pic=True),
    "ppt/slides/_rels/slide1.xml.rels": CT + f'<Relationships {REL}><Relationship Id="rId2" Type="image" Target="../media/image1.png"/><Relationship Id="rId5" Type="notesSlide" Target="../notesSlides/notesSlide1.xml"/></Relationships>',
    "ppt/slides/slide2.xml": slide("Opening", ["Welcome", "Agenda &amp; goals"]),
    "ppt/media/image1.png": PNG,
    "ppt/notesSlides/notesSlide1.xml": CT + f'<p:notes {P}><p:cSld><p:spTree><p:sp><p:nvSpPr><p:cNvPr id="2" name="N"/><p:cNvSpPr/><p:nvPr><p:ph type="body"/></p:nvPr></p:nvSpPr><p:txBody><a:p><a:r><a:t>Say hello</a:t></a:r></a:p></p:txBody></p:sp></p:spTree></p:cSld></p:notes>',
})

# LibreOffice-made files (skipped when soffice is missing).
soffice = shutil.which("soffice")
if soffice:
    tmp = tempfile.mkdtemp()
    html = os.path.join(tmp, "lo.html")
    with open(html, "w") as f:
        f.write("""<html><body><h1>Office test</h1><p>Hello <b>world</b>.</p>
<ul><li>Apple</li><li>Banana</li></ul><ol><li>One</li><li>Two</li></ol>
<table border="1"><tr><td>a1</td><td>b1</td></tr><tr><td>a2</td><td>b2</td></tr></table>
<h2>Second</h2><p>End.</p></body></html>""")
    csv = os.path.join(tmp, "lo.csv")
    with open(csv, "w") as f:
        f.write("City,Population\nTehran,9500000\n\"Shiraz, Fars\",1900000\n")
    env = dict(os.environ, HOME=tmp)
    prof = "-env:UserInstallation=file://" + tmp + "/profile"
    run = lambda *a: subprocess.run([soffice, prof, "--headless", *a, "--outdir", tmp], env=env, check=True, capture_output=True)
    run("--writer", "--convert-to", "docx:MS Word 2007 XML", html)
    run("--convert-to", "xlsx", csv)
    fodp = os.path.join(tmp, "lo.fodp")
    with open(fodp, "w") as f:
        f.write("""<?xml version="1.0" encoding="UTF-8"?>
<office:document xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" xmlns:draw="urn:oasis:names:tc:opendocument:xmlns:drawing:1.0"
 xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0" xmlns:presentation="urn:oasis:names:tc:opendocument:xmlns:presentation:1.0"
 xmlns:svg="urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0" office:version="1.3" office:mimetype="application/vnd.oasis.opendocument.presentation">
<office:body><office:presentation>
<draw:page draw:name="p1"><draw:frame presentation:class="title" svg:x="1cm" svg:y="1cm" svg:width="20cm" svg:height="3cm"><draw:text-box><text:p>Intro slide</text:p></draw:text-box></draw:frame>
<draw:frame presentation:class="outline" svg:x="1cm" svg:y="5cm" svg:width="20cm" svg:height="10cm"><draw:text-box><text:list><text:list-item><text:p>Point one</text:p></text:list-item><text:list-item><text:p>Point two</text:p></text:list-item></text:list></draw:text-box></draw:frame></draw:page>
<draw:page draw:name="p2"><draw:frame presentation:class="title" svg:x="1cm" svg:y="1cm" svg:width="20cm" svg:height="3cm"><draw:text-box><text:p>Thanks</text:p></draw:text-box></draw:frame></draw:page>
</office:presentation></office:body></office:document>""")
    run("--convert-to", "pptx", fodp)
    shutil.copy(os.path.join(tmp, "lo.docx"), os.path.join(OUT, "lo.docx"))
    shutil.copy(os.path.join(tmp, "lo.xlsx"), os.path.join(OUT, "lo.xlsx"))
    shutil.copy(os.path.join(tmp, "lo.pptx"), os.path.join(OUT, "lo.pptx"))
    shutil.rmtree(tmp, ignore_errors=True)
print("fixtures in", os.path.abspath(OUT))
