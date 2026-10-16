# -*- coding: utf-8 -*-
"""生成 JVM 单元测试用的课表 PDF fixture（在仓库根目录或 kobi/ 下运行均可）。

用法（从 kobi/ 目录）：
    python tools/make-test-pdf.py                  # 生成下面两个 fixture
    python tools/make-test-pdf.py <输出路径>        # 只生成第一个（保持旧用法）
只用 Python 标准库（re / zlib / os / sys），不需要任何第三方依赖。

产出两个 fixture：

1. `app/src/test/resources/timetable-fixture.pdf` —— 基础结构夹具（3 个格子、单节格）。
2. `app/src/test/resources/timetable-compact-fixture.pdf` —— **紧凑版**，复刻真实教务课表 PDF
   里那些「只有按列切开才认得出来」的结构：
   - 正文用 8~10pt 小字号、行距紧到相邻两格的行会挨在一起；
   - 课程号 `/(1000000002)00000002` 这类 21 字符长串几乎铺满整列宽（106pt）；
   - 课程名跨行（`★中级财务会计模拟实` + `验`）；
   - 块首符号 `★` 出现在**行末**（`…,4119★大学外语A`）；
   - 页脚备注行（`公选课程…`）横跨两个星期列；
   - 同一个格子里两门课的文字（构造上不重叠，但行序交错）。

两个 fixture 都刻意与真实教务课表 PDF（MuPDF 打印输出）保持同构：
- 横向 A4，MediaBox [0 0 841 595]；
- 经典 xref 表 + trailer + startxref；
- 页面内容流 FlateDecode 压缩；
- 字体 Type0 + STSong-Light-UniGB-UCS2-H + /Encoding /UniGB-UCS2-H（即 UTF-16BE 字节）；
- 正文文字**逐字定位**（每个字一条 BT / Tf / Tm / Tj / ET），与真实文件一致；
- **表格网格用 m/l 描边的直线段画出**，并且**合并单元格中间那条横线不画** ——
  这是「节次范围」唯一精确来源，也是这两个 fixture 最要紧的结构特征。

内容全部虚构，不含任何真实学号、姓名、学校信息。
"""

import os
import sys
import zlib

OUT = sys.argv[1] if len(sys.argv) > 1 else "app/src/test/resources/timetable-fixture.pdf"
OUT_COMPACT = "app/src/test/resources/timetable-compact-fixture.pdf"

PAGE_W, PAGE_H = 841.0, 595.0
FONT_SIZE = 11.0

# 列边界与真实教务课表一致：最左是节次列，右侧 7 个星期列
COL_BOUNDS = [28.0, 70.0, 176.0, 282.0, 388.0, 494.0, 600.0, 706.0, 812.0]
HEADER_Y = 527.0

# 节次号文字的基线 y（落在各自格子正中）
SECTION_Y = [502.0, 476.0, 445.0, 415.0, 385.0, 355.0]

# 格子行边界（升序，PDF 坐标 y 向上）。相邻两条围成一个节次格：
#   节1 = [489.0, 515.0]   节2 = [460.5, 489.0]   节3 = [430.0, 460.5]
#   节4 = [400.0, 430.0]   节5 = [370.0, 400.0]   节6 = [340.0, 370.0]
ROW_BOUNDS = [340.0, 370.0, 400.0, 430.0, 460.5, 489.0, 515.0]

# 合并单元格：画水平边界线时要跳过的 (列号, y)。列号 1..7 对应 周一..周日。
# 星期二第 1-2 节合并成一个两节高的格子 —— 中间那条 y=489 的横线不画。
MERGED_LINES = {(2, 489.0)}

# 参与渲染的节次数
SECTION_COUNT = len(SECTION_Y)


def cell_top_y(section):
    """第 section 节（1 起）所属格子的**上边界** y。"""
    return ROW_BOUNDS[len(ROW_BOUNDS) - section]


def is_full(ch):
    c = ord(ch)
    return (0x1100 <= c <= 0x115F or 0x2600 <= c <= 0x27BF or 0x2E80 <= c <= 0x303E
            or 0x3041 <= c <= 0x33FF or 0x3400 <= c <= 0x4DBF or 0x4E00 <= c <= 0x9FFF
            or 0xA000 <= c <= 0xA4CF or 0xAC00 <= c <= 0xD7A3 or 0xF900 <= c <= 0xFAFF
            or 0xFE30 <= c <= 0xFE4F or 0xFF00 <= c <= 0xFF60 or 0xFFE0 <= c <= 0xFFE6)


def text_runs(text, x, y, fs=FONT_SIZE):
    """把一个字符串拆成逐字的 (x, y, char, fs)，x 递增规则与 Kotlin 侧宽度估算一致。"""
    runs = []
    cx = x
    for ch in text:
        runs.append((cx, y, ch, fs))
        cx += fs * (1.0 if is_full(ch) else 0.5)
    return runs


def pdf_string(s):
    """按 PDF 字面串规则转义：先转成 UTF-16BE 字节，再逐字节转义。"""
    out = bytearray()
    for b in s.encode("utf-16-be"):
        if b in (0x28, 0x29, 0x5C):          # ( ) \
            out += b"\\" + bytes([b])
        elif b == 0x0D:
            out += b"\\r"
        elif b == 0x0A:
            out += b"\\n"
        elif b == 0x09:
            out += b"\\t"
        elif b == 0x08:
            out += b"\\b"
        elif b == 0x0C:
            out += b"\\f"
        elif 32 <= b < 127:
            out.append(b)
        else:
            out += ("\\%03o" % b).encode("ascii")
    return bytes(out)


def render_runs(parts, runs):
    """把逐字文本写成 BT / Tf / Tm / Tj / ET。"""
    for x, y, ch, fs in runs:
        parts += b"BT\n0 0 0 rg\n/F1 %.1f Tf\n1 0 0 1 %.1f %.1f Tm\n(%s)Tj\nET\n" % (
            fs, x, y, pdf_string(ch))


def build_content():
    parts = bytearray()

    # 1) 表格竖线：节次列左边界 + 8 条列边界（`m` / `l` / `S`，与真实文件同一种画法）
    parts += b"1 w\n0 0 0 RG\n"
    for x in COL_BOUNDS:
        parts += b"%.1f %.1f m\n%.1f %.1f l\nS\n" % (x, ROW_BOUNDS[0], x, ROW_BOUNDS[-1])

    # 2) 表格横线：每列各自一行一格；合并格子中间那条线跳过
    for column in range(1, 8):
        left = COL_BOUNDS[column]
        right = COL_BOUNDS[column + 1]
        for y in ROW_BOUNDS:
            if (column, y) in MERGED_LINES:
                continue
            parts += b"%.1f %.1f m\n%.1f %.1f l\nS\n" % (left, y, right, y)

    # 3) 文本：逐字 BT / Tf / Tm / Tj / ET
    runs = []

    # 表头：节次 + 星期一…星期日（放在星期列中央）
    runs += text_runs("节次", 40.0, HEADER_Y)
    for i, name in enumerate(["星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"]):
        center = (COL_BOUNDS[i + 1] + COL_BOUNDS[i + 2]) / 2.0
        runs += text_runs(name, center - len(name) * FONT_SIZE / 2.0, HEADER_Y)

    # 最左列节次号
    for i, y in enumerate(SECTION_Y):
        runs += text_runs(str(i + 1), 46.0, y)

    # 4) 课程格子。每格 4 行元数据：★课程名 / 课程号 / 学分+教师 / 周次+地点。
    #    文字与真实教务系统一样**从格子左上角开始往下排**（格子装不下就向下溢出）。
    #    (列号 1..7 → 周一..周日；节次号 1 起)
    cells = [
        # 星期二（第 2 列）第 1 节 —— 该格由节 1-2 合并而成
        (2, 1, ["★高等数学", "(0000)0001", "3.0/张三", "【1-16周】,3301"]),
        # 星期二（第 2 列）第 3 节 —— 单节格
        (2, 3, ["★大学物理", "(0000)0002", "2.5/李四", "【1-8周(单)】,2105"]),
        # 星期四（第 4 列）第 5 节 —— 单节格
        (4, 5, ["★数据结构", "(0000)0003", "4.0/王五", "【2-10周】,A301"]),
    ]
    for column, section, cell_lines in cells:
        left = COL_BOUNDS[column]
        base_y = cell_top_y(section) - 6.0
        for order, line in enumerate(cell_lines):
            runs += text_runs(line, left + 4.0, base_y - order * 14.0)

    render_runs(parts, runs)
    return bytes(parts)


# ===================== 紧凑版（复刻真实教务课表 PDF 的行排布） =====================

# 与真实文件一致：列边界偏移到 [30, 70, 176, …, 812]，列宽 106pt
COMPACT_COL_BOUNDS = [30.0, 70.0, 176.0, 282.0, 388.0, 494.0, 600.0, 706.0, 812.0]
COMPACT_HEADER_Y = 527.0

# 12 节的节次号基线 y（真实文件实测值）
COMPACT_SECTION_Y = [502.0, 476.0, 437.0, 377.0, 334.0, 300.0, 262.0, 229.0, 179.0, 112.0, 62.0, 36.0]

# 每列各自的行边界线（真实文件里是各列并集，合并格缺中间那条线）
COMPACT_ROW_BOUNDS = [18.0, 30.0, 47.0, 81.0, 148.0, 215.0, 248.0, 281.0, 324.0, 349.0,
                      409.0, 469.0, 487.0, 522.0, 537.0]

# 合并单元格（缺中间横线）：星期二第 1-2 节、星期三第 7-8 节。
# 注意线要选在**相邻两节的分界**上：节1(502) 与节2(476) 之间是 487；节7(262) 与节8(229) 之间是 248。
COMPACT_MERGED = {(2, 487.0), (3, 248.0)}

NAME_FS = 10.0   # 课程名字号（真实：10pt）
META_FS = 8.0    # 课程号/教师/周次字号（真实：8pt）
LINE_STEP = 14.0  # 同格相邻行距

# 每个格子按真实坐标排出「一行行文字」；(列号, 行 y, 文本)
COMPACT_LINES = [
    # 星期一第 3 节：基础四行（★课程名 / 学分括号 + 课程号 / 学分 + 教师 / 周次 + 地点）
    (1, 455.0, "★体育", NAME_FS),
    (1, 441.0, "(3)/(1000000001)00000001", META_FS),
    (1, 427.0, "3.0/甘老师", META_FS),
    (1, 413.0, "/【7-17周】,A301", META_FS),

    # 星期二第 1-2 节（合并格）：课程名跨行（`★中级财务会计模拟实` + `验`）
    (2, 512.0, "★中级财务会计模拟实", NAME_FS),
    (2, 498.0, "验", NAME_FS),
    (2, 484.0, "/(1000000002)00000002", META_FS),
    (2, 470.0, "05/杨明", META_FS),
    (2, 456.0, "/【1-4,6-17周】,2117", META_FS),

    # 星期三第 7-8 节（合并格）：同一格两门课，**两门课的对应行落在完全相同的 y**。
    # 这是真实教务课表的形态（实测真实 PDF 里周四第 5-6 节的两门课在 y=305 上都有一行），
    # 也是「按 y 分桶聚行」必然出错的地方：分桶会把 `★财务管理` 与 `★计量经济学` 拼成一行，
    # 课程名、教师、地点一起报废。绘制顺序是唯一能把它们分开的信息。
    # 每行宽度都控制在列宽（106pt）以内 —— 超宽会溢到相邻列，那是另一个问题，不该混进来。
    # （「★ 出现在行末」的场景已由 TimetableLayoutParserTest 的单测覆盖，这里不再重复。）
    (3, 275.0, "★财务管理", NAME_FS),
    (3, 264.0, "/(1000000003)00000003", META_FS),
    (3, 253.0, "05/宋满/【2-4周】,2413", META_FS),
    (3, 275.0, "★计量经济学", NAME_FS),
    (3, 264.0, "/(1000000004)00000004", META_FS),
    (3, 253.0, "02/庄舟/【5-9周】,3310", META_FS),

    # 星期六第 3-4 节：课程名跨行 + 同格两门课
    (6, 455.0, "★“橙爱橙家”宿舍清洁", NAME_FS),
    (6, 441.0, "及安全防护项目", NAME_FS),
    (6, 427.0, "/(1000000005)00000005", META_FS),
    (6, 413.0, "05/赵亮/【14周】,4119", META_FS),
    (6, 399.0, "★大学外语A", NAME_FS),
    (6, 385.0, "(3)/(1000000006)00000006", META_FS),
    (6, 371.0, "05/赖声/【5周】,3313", META_FS),
]

# 页脚备注：横跨星期一、星期二两列（真实文件里正是这一行按列切开后变成碎片）
COMPACT_FOOTER_Y = 22.0
COMPACT_FOOTER = ("公选课程:社会主义发展史[网络课]【吴老师（4-15）】", 70.0)


def build_content_compact():
    parts = bytearray()
    parts += b"1 w\n0 0 0 RG\n"
    for x in COMPACT_COL_BOUNDS:
        parts += b"%.1f %.1f m\n%.1f %.1f l\nS\n" % (
            x, COMPACT_ROW_BOUNDS[0], x, COMPACT_ROW_BOUNDS[-1])

    for column in range(1, 8):
        left = COMPACT_COL_BOUNDS[column]
        right = COMPACT_COL_BOUNDS[column + 1]
        for y in COMPACT_ROW_BOUNDS:
            if (column, y) in COMPACT_MERGED:
                continue
            parts += b"%.1f %.1f m\n%.1f %.1f l\nS\n" % (left, y, right, y)

    runs = []
    runs += text_runs("节次", 41.0, COMPACT_HEADER_Y)
    for i, name in enumerate(["星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"]):
        center = (COMPACT_COL_BOUNDS[i + 1] + COMPACT_COL_BOUNDS[i + 2]) / 2.0
        runs += text_runs(name, center - len(name) * NAME_FS / 2.0, COMPACT_HEADER_Y)
    for i, y in enumerate(COMPACT_SECTION_Y):
        runs += text_runs(str(i + 1), 55.0, y)

    for column, y, text, fs in COMPACT_LINES:
        # 课程名居中、其余左对齐 —— 与真实教务课表一致
        if text.startswith("★"):
            width = sum(fs * (1.0 if is_full(c) else 0.5) for c in text)
            center = (COMPACT_COL_BOUNDS[column] + COMPACT_COL_BOUNDS[column + 1]) / 2.0
            left = center - width / 2.0
        else:
            left = COMPACT_COL_BOUNDS[column] + 4.0
        runs += text_runs(text, left, y, fs)

    footer, footer_x = COMPACT_FOOTER
    runs += text_runs(footer, footer_x, COMPACT_FOOTER_Y, META_FS)

    render_runs(parts, runs)
    return bytes(parts)


def build_pdf(content):
    compressed = zlib.compress(content, 9)

    objects = []
    objects.append(b"<< /Type /Catalog /Pages 2 0 R >>")
    objects.append(b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
    objects.append(
        ("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 %.1f %.1f] "
         "/Resources << /ProcSet [/PDF /Text] /Font << /F1 4 0 R >> >> "
         "/Contents 5 0 R >>" % (PAGE_W, PAGE_H)).encode("ascii"))
    objects.append(
        b"<< /Type /Font /Subtype /Type0 /BaseFont /STSong-Light-UniGB-UCS2-H "
        b"/Encoding /UniGB-UCS2-H /DescendantFonts [6 0 R] >>")
    objects.append(
        b"<< /Filter /FlateDecode /Length " + str(len(compressed)).encode("ascii") +
        b" >>\nstream\n" + compressed + b"\nendstream")
    objects.append(
        b"<< /Type /Font /Subtype /CIDFontType0 /BaseFont /STSong-Light "
        b"/CIDSystemInfo << /Registry (Adobe) /Ordering (GB1) /Supplement 4 >> "
        b"/FontDescriptor 7 0 R /DW 1000 >>")
    objects.append(
        b"<< /Type /FontDescriptor /FontName /STSong-Light /Flags 4 "
        b"/FontBBox [-25 -254 1000 880] /ItalicAngle 0 /Ascent 880 /Descent -120 "
        b"/CapHeight 880 /StemV 93 >>")

    out = bytearray(b"%PDF-1.4\n%\xe2\xe3\xcf\xd3\n")
    offsets = []
    for i, body in enumerate(objects, start=1):
        offsets.append(len(out))
        out += str(i).encode("ascii") + b" 0 obj\n" + body + b"\nendobj\n"

    xref_pos = len(out)
    out += b"xref\n0 " + str(len(objects) + 1).encode("ascii") + b"\n"
    out += b"0000000000 65535 f \n"
    for off in offsets:
        out += ("%010d 00000 n \n" % off).encode("ascii")
    out += (b"trailer\n<< /Size " + str(len(objects) + 1).encode("ascii") +
            b" /Root 1 0 R >>\nstartxref\n" + str(xref_pos).encode("ascii") + b"\n%%EOF\n")
    return bytes(out)


def write_pdf(path, content):
    parent = os.path.dirname(path)
    if parent:
        os.makedirs(parent, exist_ok=True)
    data = build_pdf(content)
    with open(path, "wb") as f:
        f.write(data)
    print("wrote %s  %d bytes" % (path, len(data)))


if __name__ == "__main__":
    if len(sys.argv) > 1:
        # 保持旧用法：显式指定输出路径时只生成基础夹具
        write_pdf(OUT, build_content())
    else:
        write_pdf(OUT, build_content())
        write_pdf(OUT_COMPACT, build_content_compact())
