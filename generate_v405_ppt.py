"""
v4.0.5 PPT 生成脚本（修正排版）
基于 v4.0.2 PPT 克隆，更新版本号，新增 v4.0.4 和 v4.0.5 变更内容页。
修正：增大文本框、减小字号、设置行距避免文字重叠。
沿用 v3.22.24 风格（用户偏好）。
"""
import shutil
from pathlib import Path
from pptx import Presentation
from pptx.util import Pt, Inches, Emu
from pptx.dml.color import RGBColor
from pptx.enum.text import PP_ALIGN, MSO_AUTO_SIZE

SRC = Path(r"C:\Users\Administrator\Desktop\资产盘点拍照工具-使用说明书-v4.0.2.pptx")
DST = Path(r"C:\Users\Administrator\Desktop\资产盘点拍照工具-使用说明书-v4.0.5-new.pptx")

# 先复制
shutil.copy2(SRC, DST)

prs = Presentation(DST)

# 获取幻灯片尺寸（用于计算可用空间）
slide_width = prs.slide_width
slide_height = prs.slide_height
print(f"幻灯片尺寸: {slide_width/914400:.2f} x {slide_height/914400:.2f} 英寸")

# 全局文本替换映射
REPLACEMENTS = {
    "v4.0.2": "v4.0.5",
    "V4.0.2": "V4.0.5",
    "4.0.2": "4.0.5",
    "versionName = \"4.0.2\"": "versionName = \"4.0.5\"",
    "versionCode = 3": "versionCode = 6",
}


def replace_in_run(run):
    if not run.text:
        return
    new_text = run.text
    for old, new in REPLACEMENTS.items():
        if old in new_text:
            new_text = new_text.replace(old, new)
    if new_text != run.text:
        run.text = new_text


def walk_shapes(shapes):
    for shape in shapes:
        if shape.shape_type == 6:  # GROUP
            yield from walk_shapes(shape.shapes)
        else:
            yield shape


# 第一遍：全局版本号替换
for slide in prs.slides:
    for shape in walk_shapes(slide.shapes):
        if not shape.has_text_frame:
            continue
        for para in shape.text_frame.paragraphs:
            for run in para.runs:
                replace_in_run(run)

# 颜色常量
COLOR_TITLE = RGBColor(0x21, 0x21, 0x21)
COLOR_ACCENT = RGBColor(0x21, 0x96, 0xF3)
COLOR_TEXT = RGBColor(0x33, 0x33, 0x33)


def add_changelog_slide(prs, title, changes):
    """添加一页变更说明（优化排版，避免文字重叠）

    优化点：
    - 文本框宽度增大到 12.3 英寸（利用全宽）
    - 文本框高度增大到 6.0 英寸
    - 子标题字号 14pt，正文 11pt
    - 段落 line_spacing=1.0 + space_after=Pt(2)，避免行距过大或过小
    - 空行用 space_before 替代，减少行数
    """
    blank_layout = prs.slide_layouts[6]
    new_slide = prs.slides.add_slide(blank_layout)

    # 标题
    txBox = new_slide.shapes.add_textbox(
        Inches(0.5), Inches(0.3), Inches(12.3), Inches(0.7)
    )
    tf = txBox.text_frame
    tf.word_wrap = True
    tf.auto_size = MSO_AUTO_SIZE.NONE
    p = tf.paragraphs[0]
    p.alignment = PP_ALIGN.LEFT
    p.space_after = Pt(0)
    p.space_before = Pt(0)
    run = p.add_run()
    run.text = title
    run.font.size = Pt(26)
    run.font.bold = True
    run.font.color.rgb = COLOR_TITLE
    run.font.name = "Microsoft YaHei"

    # 内容文本框：充分利用幻灯片空间
    content_box = new_slide.shapes.add_textbox(
        Inches(0.5), Inches(1.15), Inches(12.3), Inches(6.1)
    )
    ctf = content_box.text_frame
    ctf.word_wrap = True
    ctf.auto_size = MSO_AUTO_SIZE.NONE

    for idx, (text, is_bold, is_gap) in enumerate(changes):
        p = ctf.paragraphs[0] if idx == 0 else ctf.add_paragraph()
        p.alignment = PP_ALIGN.LEFT
        p.line_spacing = 1.0
        if is_gap:
            # 空行用较小间距
            p.space_before = Pt(4)
            p.space_after = Pt(0)
            run = p.add_run()
            run.text = ""
            run.font.size = Pt(6)
        else:
            p.space_before = Pt(0)
            p.space_after = Pt(2)
            run = p.add_run()
            run.text = text
            run.font.size = Pt(14) if is_bold else Pt(11)
            run.font.bold = is_bold
            run.font.color.rgb = COLOR_ACCENT if is_bold else COLOR_TEXT
            run.font.name = "Microsoft YaHei"


# v4.0.4 变更内容（精简版）
# 格式：(文本, 是否加粗子标题, 是否为间隔空行)
v404_changes = [
    ("1. 清空数据/缓存修复", True, False),
    ("   注册 progress key，扩展缓存清理范围（photos/thumbnails/reports/visit_notes/progress.json）", False, False),
    ("", False, True),
    ("2. 相机双指缩放 + 广角", True, False),
    ("   双指缩放手势 + ZoomControlBar（Slider 缩放 + 广角按钮）", False, False),
    ("", False, True),
    ("3. 最近文件持久化修复", True, False),
    ("   takePersistableUriPermission 权限检查，重启后仍可访问", False, False),
    ("", False, True),
    ("4. 查看已拍按钮实现", True, False),
    ("   PhotoGallerySheet 弹窗展示已拍照片缩略图", False, False),
    ("", False, True),
    ("5. 客户条目布局调整", True, False),
    ("   操作按钮移至右侧，计数左对齐 Checkbox，已完成条目浅绿背景", False, False),
    ("", False, True),
    ("6. 搜索新增「未走访」过滤", True, False),
    ("   一键筛选 photoCount=0 的未走访条目", False, False),
]

add_changelog_slide(prs, "v4.0.4 更新内容（2026-07-08）", v404_changes)

# v4.0.5 变更内容（精简版）
v405_changes = [
    ("1. 条目操作按钮竖向排列", True, False),
    ("   拍照/查看已拍/备注改为竖向 Column，给序号/客户名/地址留更多空间", False, False),
    ("", False, True),
    ("2. 空状态展示最近文件", True, False),
    ("   首次进入 App 直接展示最近打开的 5 个文件，点击即加载", False, False),
    ("", False, True),
    ("3. 水印设置", True, False),
    ("   设置页新增水印设置卡片：启用开关/字号(大中小)/位置(四角)/不透明度滑块", False, False),
    ("   DataStore 持久化，相机拍照读取用户配置替代硬编码", False, False),
    ("", False, True),
    ("4. 关于增加联系方式", True, False),
    ("   设置页关于卡片新增：15940454123（微信同）", False, False),
    ("", False, True),
    ("5. 已完成绿色加深", True, False),
    ("   Green 50(#E8F5E9) → Green 100(#C8E6C9)，更醒目", False, False),
    ("", False, True),
    ("6. 地址搜索合并", True, False),
    ("   「地址概」「地址详」合并为单一「地址」，同时匹配两列", False, False),
    ("", False, True),
    ("7. 加载弹窗", True, False),
    ("   inline 小转圈 → 居中弹窗 + 加粗转圈 + 中文「加载中...」", False, False),
    ("", False, True),
    ("8. 添加查勘条目", True, False),
    ("   搜索框下方新增按钮，弹出三输入框（序号/借款人/地址）", False, False),
    ("   保存后追加到 Excel 末尾，不覆盖原有内容，自动刷新列表", False, False),
]

add_changelog_slide(prs, "v4.0.5 更新内容（2026-07-08）", v405_changes)

prs.save(DST)
print(f"PPT saved: {DST}")
print(f"Total slides: {len(prs.slides)}")
