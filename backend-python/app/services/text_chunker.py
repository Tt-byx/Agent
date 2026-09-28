import re
import logging

logger = logging.getLogger(__name__)


def chunk_text(text: str, chunk_size: int = 400, overlap: int = 50, max_chunks: int = 2000) -> list[dict]:
    """将文本切分为带重叠的 chunk

    策略：先按段落分割，超长段落按句子分割，超长句子按字数截断。
    max_chunks: 最大 chunk 数量，防止超大文件产生过多 chunks
    """
    if not text or not text.strip():
        return []

    # 如果文本过长，按 max_chunks * chunk_size 截断
    max_text_len = max_chunks * chunk_size
    if len(text) > max_text_len:
        logger.warning(f"文本过长 ({len(text)} 字符)，截断至 {max_text_len} 字符")
        text = text[:max_text_len]

    paragraphs = re.split(r"\n\s*\n", text.strip())
    chunks = []

    for para in paragraphs:
        para = para.strip()
        if not para:
            continue

        if len(para) <= chunk_size:
            chunks.append(para)
        else:
            sentences = re.split(r"(?<=[。！？；\n])", para)
            current = ""
            for sent in sentences:
                sent = sent.strip()
                if not sent:
                    continue
                if len(current) + len(sent) <= chunk_size:
                    current += sent
                else:
                    if current:
                        chunks.append(current)
                    if len(sent) > chunk_size:
                        for i in range(0, len(sent), chunk_size - overlap):
                            chunks.append(sent[i : i + chunk_size])
                    else:
                        current = sent
                        continue
                    current = ""
            if current:
                chunks.append(current)

    result = []
    for i, chunk in enumerate(chunks):
        entry = {"text": chunk, "index": i}
        if i > 0 and overlap > 0:
            prev_text = chunks[i - 1]
            overlap_text = prev_text[-overlap:] if len(prev_text) > overlap else prev_text
            entry["text"] = overlap_text + chunk
        result.append(entry)

    logger.info(f"文本切片完成：{len(result)} 个 chunk（原文 {len(text)} 字符）")
    return result
