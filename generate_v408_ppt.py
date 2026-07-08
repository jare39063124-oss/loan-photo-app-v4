"""
v4.0.8 PPT 生成脚本
基于 v4.0.7 PPT，全局版本号精确替换 v4.0.7 -> v4.0.8 + 新增 v4.0.8 变更页
沿用 v3.22.24 风格（与 v4.0.6/v4.0.7 变更页一致）
"""
import re
import shutil
from pathlib import Path
from pptx import Presentation
from pptx.util import Pt, Inches
from pptx.dml.color import RGBColor
from pptx.enum.text import PP_ALIGN, MSO_AUTO_SIZE

SRC = Path(r"C:\Users\Administrator\Desktop\资产盘点拍照工具-使用说明书-v4.0.7.pptx")
DST = Path(r"C:\Users\Administrator\Desktop\资产盘点拍照工具-使用说明书-v4.0.8.pptx")

if not SRC.exists():
    raise FileNotFoundError(f"源文件不存在: {SRC}")

# === 复制 v4.0.7 -> v4.0.8 ===
shutil.copy2(SRC, DST)
prs = Presentation(DST)

print(f"幻灯片尺寸: {prs.slide_width/914400:.2f} x {prs.slide_height/914400:.2f} 英寸")
print(f"原始页数: {len(prs.slides)}")

# === 第一部分：全局版本号精确替换 ===
# v4.0.7 -> v4.0.8, V4.0.7 -> V4.0.8, 4.0.7 -> 4.0.8
# 用负向先行/后行断言避免误伤 v4.0.7x / 14.0.7 / 4.0.7.1 等
def replace_version(text):
    if not text:
        return text
    # 先替换带前缀的（v / V），再替换纯数字的
    text = re.sub(r'v4\.0\.7(?![0-9a-zA-Z])', 'v4.0.8', text)
    text = re.sub(r'V4\.0\.7(?![0-9a-zA-Z])', 'V4.0.8', text)
    # 纯数字 4.0.7 前后均不能是字母/数字，避免误伤 14.0.7 / 4.0.7.1
    text = re.sub(r'(?<![0-9a-zA-Z])4\.0\.7(?![0-9a-zA-Z])', '4.0.8', text)
    return text

def walk_shapes(shapes):
    for shape in shapes:
        if shape.shape_type == 6:  # GROUP
            yield from walk_shapes(shape.shapes)
        else:
            yield shape

replace_count = 0
for slide in prs.slides:
    for shape in walk_shapes(slide.shapes):
        if not shape.has_text_frame:
            continue
        for para in shape.text_frame.paragraphs:
            for run in para.runs:
                if run.text:
                    new_text = replace_version(run.text)
                    if new_text != run.text:
                        run.text = new_text
                        replace_count += 1

print(f"版本号替换 run 数: {replace_count}")

# === 第二部分：新增 v4.0.8 变更页 ===
COLOR_TITLE = RGBColor(0x21, 0x21, 0x21)
COLOR_ACCENT = RGBColor(0x21, 0x96, 0xF3)
COLOR_TEXT = RGBColor(0x33, 0x33, 0x33)

def add_changelog_slide(prs, title, changes):
    blank_layout = prs.slide_layouts[6]
    new_slide = prs.slides.add_slide(blank_layout)

    # 标题：26pt 加粗 #212121
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

    # 内容文本框：Inches(0.5), Inches(1.15), Inches(12.3), Inches(6.1)
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
            # 空行：6pt，space_before 4pt
            p.space_before = Pt(4)
            p.space_after = Pt(0)
            run = p.add_run()
            run.text = ""
            run.font.size = Pt(6)
        else:
            # 正文：space_before 0pt，space_after 2pt
            p.space_before = Pt(0)
            p.space_after = Pt(2)
            run = p.add_run()
            run.text = text
            # 副标题 14pt Accent 蓝加粗，正文 11pt #333333
            run.font.size = Pt(14) if is_bold else Pt(11)
            run.font.bold = is_bold
            run.font.color.rgb = COLOR_ACCENT if is_bold else COLOR_TEXT
            run.font.name = "Microsoft YaHei"

v408_changes = [
    ("1. 条目备注回写 Excel（修复 v4.0.5 问题）", True, False),
    ("   客户列表保存备注时同时写入 Excel 文件备注列，不再仅存 App 内部", False, False),
    ("   写回失败明确提示「Excel 写入失败，备注仅保存在 App 内」", False, False),
    ("   添加查勘条目持久化验证（SAF 写权限已确认 READ+WRITE）", False, False),
    ("", False, True),
    ("2. AI 日报表模板清理与列对齐（修复「参考模版」问题）", True, False),
    ("   清理 report_template.xlsx 全部示例数据（东港市禽蛋市场有限公司等 10+ 示例客户）", False, False),
    ("   模板仅保留表头（序号/日期/勘查业务贷款人名称/抵押物具体情况/现状描述/备注）+ 底部说明", False, False),
    ("   列对齐修复：A=序号 B=日期 C=客户名称 D=抵押物 E=现状描述 F=备注", False, False),
    ("   每条记录自动填充序号与当天日期", False, False),
    ("", False, True),
    ("3. AI 提示词重写（修复「装修未知/使用情况未知」问题）", True, False),
    ("   移除强制填写使用情况/楼层/装修/维护/周边环境的要求", False, False),
    ("   field_description 仅描述实际备注中提到的信息，未提及不输出", False, False),
    ("   禁止「装修未知」「使用情况未知」等模板化表述", False, False),
    ("   summary 基础句固定：经实地查勘，抵押物暂未发现明显异常，建议关注企业经营情况，维护我行资金安全", False, False),
    ("", False, True),
    ("4. AI 日报表保存与分享", True, False),
    ("   取消自动存 App 私有目录，改为结果弹窗（文件名+大小）", False, False),
    ("   新增「分享」按钮（微信/钉钉/邮件）+「保存到本地」（SAF 选路径）", False, False),
    ("   复用 v4.0.7 ExportResultDialog + shareExportedFile 链路", False, False),
    ("", False, True),
    ("5. AI 生成页面「特殊日志」", True, False),
    ("   新增补充说明/特殊日志多行输入区，支持取消/保存/生成", False, False),
    ("   保存后下次进入页面回显，生成时作为参考注入 AI 提示词", False, False),
    ("   新建 SpecialLogRepository 按 excelUriMd5 持久化", False, False),
    ("", False, True),
    ("6. 综合 v4.0.6 + v4.0.7 能力，发布 v4.0.8", True, False),
    ("   保留 R8 full mode 混淆 + 签名 + 抗逆向 + 导出分享 + 恢复出厂警告", False, False),
    ("   versionCode=9, versionName=4.0.8", False, False),
]

add_changelog_slide(prs, "v4.0.8 更新内容（2026-07-09）", v408_changes)

prs.save(DST)
print(f"\nPPT saved: {DST}")
print(f"Total slides: {len(prs.slides)}")
