"""文本处理工具"""

import re


def strip_markdown(text: str) -> str:
    """剥离 Markdown 格式符号，保留纯文本内容，适合 TTS 朗读。

    处理：
    - 标记符号：**bold**, *italic*, ~~strikethrough~~, `code`, ```code block```
    - 标题：# Heading → 去掉 # 号
    - 列表：- item / 1. item → 去掉序号和符号
    - 链接：[text](url) → text
    - 图片：![alt](url) → 去掉整行
    - 水平线：--- 或 *** → 去掉
    - 表格：| a | b | → a, b
    - 多余空行：合并为一个
    """
    if not text:
        return text

    # 代码块：```...``` → 去掉
    text = re.sub(r"```[\s\S]*?```", "", text)

    # 行内代码：`...` → 内容
    text = re.sub(r"`([^`]*)`", r"\1", text)

    # 图片：![alt](url) → 去掉
    text = re.sub(r"!\[[^\]]*\]\([^)]*\)", "", text)

    # 链接：[text](url) → text
    text = re.sub(r"\[([^\]]*)\]\([^)]*\)", r"\1", text)

    # 标题标记：### Heading → Heading (行首)
    text = re.sub(r"^#{1,6}\s+", "", text, flags=re.MULTILINE)

    # 残留的 # 符号（行内单独出现的 #）
    text = re.sub(r"#{1,6}\s*", "", text)

    # 加粗+斜体：***text*** / ___text___
    text = re.sub(r"\*{3}([^*]+)\*{3}", r"\1", text)
    text = re.sub(r"_{3}([^_]+)_{3}", r"\1", text)

    # 加粗：**text** / __text__
    text = re.sub(r"\*{2}([^*]+)\*{2}", r"\1", text)
    text = re.sub(r"_{2}([^_]+)_{2}", r"\1", text)

    # 斜体：*text* / _text_
    text = re.sub(r"\*([^*]+)\*", r"\1", text)
    text = re.sub(r"(?<!\w)_([^_]+)_(?!\w)", r"\1", text)

    # 删除线：~~text~~
    text = re.sub(r"~~([^~]+)~~", r"\1", text)

    # 引用块：> text → text
    text = re.sub(r"^>\s*", "", text, flags=re.MULTILINE)

    # 无序列表：- text / * text → text
    text = re.sub(r"^[\-\*]\s+", "", text, flags=re.MULTILINE)

    # 有序列表：1. text → text
    text = re.sub(r"^\d+\.\s+", "", text, flags=re.MULTILINE)

    # 水平线
    text = re.sub(r"^[-*_]{3,}\s*$", "", text, flags=re.MULTILINE)

    # 表格行：| a | b | → a, b
    def clean_table_row(line):
        # 去掉分隔行 (| --- | --- |)
        if re.match(r"^\|[\s\-:]+\|$", line.strip()):
            return ""
        cells = [c.strip() for c in line.split("|") if c.strip()]
        return "，".join(cells) if cells else ""

    lines = text.split("\n")
    lines = [clean_table_row(l) if l.strip().startswith("|") else l for l in lines]
    text = "\n".join(lines)

    # 合并多余空行
    text = re.sub(r"\n{3,}", "\n\n", text)

    return text.strip()
