"""生成完全自造的矩形字形，不复制或分发系统字体。需 fonttools==4.60.2。"""
from pathlib import Path
from fontTools.fontBuilder import FontBuilder
from fontTools.pens.ttGlyphPen import TTGlyphPen
from fontTools.pens.t2CharStringPen import T2CharStringPen
from fontTools.ttLib import TTCollection, TTFont
from fontTools.cffLib import FDArrayIndex, FDSelect, FontDict, PrivateDict

root = Path(__file__).resolve().parents[1] / 'agentflow-server/src/test/resources/ofd-fonts'
root.mkdir(parents=True, exist_ok=True)
names = ['.notdef', 'space', 'A', 'zhong', 'supplementary']
characters = {32: 'space', 65: 'A', 0x4E2D: 'zhong', 0x20000: 'supplementary'}

def build(name, width, units=1000, cff=False, glyph_names=names):
    builder = FontBuilder(units, isTTF=not cff)
    builder.setupGlyphOrder(glyph_names)
    builder.setupCharacterMap(characters)
    glyphs = {}
    for glyph in glyph_names:
        pen = T2CharStringPen(width + 100, None) if cff else TTGlyphPen(None)
        if glyph != 'space':
            pen.moveTo((0, 0)); pen.lineTo((width, 0)); pen.lineTo((width, 700)); pen.lineTo((0, 700)); pen.closePath()
        glyphs[glyph] = pen.getCharString() if cff else pen.glyph()
    if cff:
        builder.setupCFF(name, {'FullName': name, 'FamilyName': name, 'Weight': 'Regular'}, glyphs, {})
    else:
        builder.setupGlyf(glyphs)
    builder.setupHorizontalMetrics({glyph: (width + 100, 0) for glyph in glyph_names})
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

def build_cid(name, top_matrix=None, matrices=(None, None), select_format=0, missing_private=False,
              cids=(10, 100, 200, 300)):
    """CID 故意与 GID 不同，且各字形宽度不同，避免错误回退到 .notdef 仍通过断言。"""
    glyph_names = names + ['extra' + str(index) for index in range(len(cids) - 4)]
    font = build(name, 300, cff=True, glyph_names=glyph_names)
    top = font['CFF '].cff.topDictIndex[0]
    rename = dict(zip(glyph_names, ['.notdef'] + ['cid' + str(cid).zfill(5) for cid in cids]))
    top.ROS = ('Adobe', 'Identity', 0)
    top.CIDCount = max(cids) + 1
    del top.Private
    if top_matrix is None:
        del top.FontMatrix
    else:
        top.FontMatrix = top_matrix
    top.FDArray = FDArrayIndex()
    for index, matrix in enumerate(matrices):
        fd = FontDict()
        fd.FontName = name + '-FD' + str(index)
        if not missing_private or index != 1:
            fd.Private = PrivateDict()
        if matrix is not None:
            fd.FontMatrix = matrix
        top.FDArray.append(fd)
    top.FDSelect = FDSelect(format=select_format)
    top.FDSelect.gidArray = [0 if index < 3 else 1 for index in range(len(glyph_names))]
    strings = {}
    for index, old in enumerate(glyph_names):
        width = [900, 0, 300, 600, 800][min(index, 4)]
        pen = T2CharStringPen(width + 100, None)
        if width:
            pen.moveTo((0, 0)); pen.lineTo((width, 0)); pen.lineTo((width, 700)); pen.lineTo((0, 700)); pen.closePath()
        cs = pen.getCharString()
        cs.private = getattr(top.FDArray[top.FDSelect.gidArray[index]], 'Private', PrivateDict())
        cs.globalSubrs = top.GlobalSubrs
        cs.fdSelectIndex = top.FDSelect.gidArray[index]
        strings[rename[old]] = cs
    top.CharStrings.charStrings = strings
    top.CharStrings.fdArray = top.FDArray
    top.CharStrings.fdSelect = top.FDSelect
    top.charset = [rename[n] for n in glyph_names]
    font.setGlyphOrder(top.charset)
    for table in font['cmap'].tables:
        table.cmap = {point: rename[glyph] for point, glyph in table.cmap.items()}
    font['hmtx'].metrics = {rename[glyph]: metrics for glyph, metrics in font['hmtx'].metrics.items()}
    return font


build_cid('AgentFlowSyntheticCID').save(root / 'cid-subset.otf')
build_cid('AgentFlowSyntheticCIDMatrix', [.001, 0, 0, .001, .1, .2],
          ([1, 0, 0, 1, 0, 0], [.5, 0, 0, 1.5, 100, 200]), 3).save(root / 'cid-matrices.otf')
build_cid('AgentFlowSyntheticCIDFDOnly', matrices=([.001, 0, 0, .001, 0, 0],
          [.0005, 0, .0002, .002, .05, .15])).save(root / 'cid-fd-only.otf')
build_cid('AgentFlowSyntheticCIDTopOnly', [.002, 0, 0, .003, .2, -.1]).save(root / 'cid-top-only.otf')
build_cid('AgentFlowSyntheticCIDBadMatrix', matrices=(None, [0, 0, 0, 0, 0, 0])).save(root / 'cid-singular-matrix.otf')
build_cid('AgentFlowSyntheticCIDNoPrivate', missing_private=True).save(root / 'cid-missing-private.otf')
build_cid('AgentFlowSyntheticCIDRange1', cids=range(10, 14)).save(root / 'cid-range1.otf')
build_cid('AgentFlowSyntheticCIDRange2', cids=range(10, 270)).save(root / 'cid-range2.otf')


def corrupt_cid(filename, source, change):
    """只改定位到的结构字段，保留可解析目录，用于证明库的宽松回退不能成为成功结果。"""
    data = bytearray((root / source).read_bytes())
    with TTFont(root / source, lazy=True) as font:
        top = font['CFF '].cff.topDictIndex[0]
        start = font.reader.tables['CFF '].offset
        positions = {key: start + top.rawDict[key] for key in ['charset', 'FDSelect']}
        positions['glyphCount'] = font.reader.tables['maxp'].offset + 4
        change(data, positions)
    (root / filename).write_bytes(data)


corrupt_cid('cid-invalid-fd.otf', 'cid-subset.otf', lambda data, pos: data.__setitem__(pos['FDSelect'] + 3, 2))
corrupt_cid('cid-invalid-range-start.otf', 'cid-matrices.otf', lambda data, pos: data.__setitem__(slice(pos['FDSelect'] + 3, pos['FDSelect'] + 5), b'\x00\x01'))
corrupt_cid('cid-invalid-range-sentinel.otf', 'cid-matrices.otf', lambda data, pos: data.__setitem__(slice(pos['FDSelect'] + 9, pos['FDSelect'] + 11), b'\x00\x04'))
corrupt_cid('cid-duplicate-cid.otf', 'cid-subset.otf', lambda data, pos: data.__setitem__(slice(pos['charset'] + 5, pos['charset'] + 7), b'\x00\x64'))
corrupt_cid('cid-inconsistent-count.otf', 'cid-subset.otf', lambda data, pos: data.__setitem__(slice(pos['glyphCount'], pos['glyphCount'] + 2), b'\x00\x06'))
corrupt_cid('cid-range-overflow.otf', 'cid-range1.otf', lambda data, pos: data.__setitem__(pos['charset'] + 3, 4))
corrupt_cid('cid-range-cid-overflow.otf', 'cid-range2.otf', lambda data, pos: data.__setitem__(slice(pos['charset'] + 1, pos['charset'] + 3), b'\xff\xff'))
print('Generated six original and fifteen CID synthetic OFD font fixtures')
