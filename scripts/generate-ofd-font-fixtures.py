"""生成完全自造的矩形字形，不复制或分发系统字体。需 fonttools==4.60.2。"""
from pathlib import Path
from fontTools.fontBuilder import FontBuilder
from fontTools.pens.ttGlyphPen import TTGlyphPen
from fontTools.pens.t2CharStringPen import T2CharStringPen
from fontTools.ttLib import TTCollection

root = Path(__file__).resolve().parents[1] / 'agentflow-server/src/test/resources/ofd-fonts'
root.mkdir(parents=True, exist_ok=True)
names = ['.notdef', 'space', 'A', 'zhong', 'supplementary']
characters = {32: 'space', 65: 'A', 0x4E2D: 'zhong', 0x20000: 'supplementary'}

def build(name, width, units=1000, cff=False):
    builder = FontBuilder(units, isTTF=not cff)
    builder.setupGlyphOrder(names)
    builder.setupCharacterMap(characters)
    glyphs = {}
    for glyph in names:
        pen = T2CharStringPen(width + 100, None) if cff else TTGlyphPen(None)
        if glyph != 'space':
            pen.moveTo((0, 0)); pen.lineTo((width, 0)); pen.lineTo((width, 700)); pen.lineTo((0, 700)); pen.closePath()
        glyphs[glyph] = pen.getCharString() if cff else pen.glyph()
    if cff:
        builder.setupCFF(name, {'FullName': name, 'FamilyName': name, 'Weight': 'Regular'}, glyphs, {})
    else:
        builder.setupGlyf(glyphs)
    builder.setupHorizontalMetrics({glyph: (width + 100, 0) for glyph in names})
    builder.setupHorizontalHeader(ascent=800, descent=-200)
    builder.setupNameTable({'familyName': name, 'styleName': 'Regular', 'uniqueFontIdentifier': name, 'fullName': name, 'psName': name})
    builder.setupOS2(sTypoAscender=800, sTypoDescender=-200, usWinAscent=800, usWinDescent=200)
    builder.setupPost(); builder.setupMaxp()
    builder.font['head'].created = builder.font['head'].modified = 2082844800
    return builder.font

a, b = build('AgentFlowSyntheticA', 600), build('AgentFlowSyntheticB', 300, units=2000)
a.save(root / 'a.ttf'); b.save(root / 'b.ttf')
collection = TTCollection(); collection.fonts = [a, b]; collection.save(root / 'two-faces.ttc')
duplicate = TTCollection(); duplicate.fonts = [a, build('AgentFlowSyntheticA', 300)]; duplicate.save(root / 'ambiguous-faces.ttc')
build('AgentFlowSyntheticCFF', 550, cff=True).save(root / 'outline.otf')
no_unicode = build('AgentFlowSyntheticLegacy', 600)
no_unicode['cmap'].tables = [no_unicode['cmap'].tables[0]]
no_unicode['cmap'].tables[0].platformID = 1; no_unicode['cmap'].tables[0].platEncID = 0
no_unicode['cmap'].tables[0].cmap = {65: 'A'}
no_unicode.save(root / 'legacy-cmap.ttf')
print('Generated six synthetic OFD font fixtures')
