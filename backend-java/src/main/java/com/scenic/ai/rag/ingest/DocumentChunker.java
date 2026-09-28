package com.scenic.ai.rag.ingest;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 文档切分器 —— 从 {@code backend-python/app/services/text_chunker.py} 逐项搬迁。
 *
 * 策略与原实现一致：
 *   先按空行分段 → 超长段落按句子切 → 超长句子按字数硬切 → 相邻 chunk 之间加尾巴重叠。
 */
@Component
@Slf4j
public class DocumentChunker {

    /** 段落分隔：空行（允许中间有空白） */
    private static final Pattern PARAGRAPH_SPLIT = Pattern.compile("\\n\\s*\\n");

    /** 句子分隔：在中文句末标点或换行之后切分（零宽后顾断言） */
    private static final Pattern SENTENCE_SPLIT = Pattern.compile("(?<=[。！？；\\n])");

    private final int chunkSize;
    private final int overlap;
    private final int maxChunks;

    public DocumentChunker(
            @Value("${rag.chunk-size:400}") int chunkSize,
            @Value("${rag.chunk-overlap:50}") int overlap,
            @Value("${rag.max-chunks-per-doc:2000}") int maxChunks) {
        this.chunkSize = Math.max(chunkSize, 1);
        this.overlap = Math.max(overlap, 0);
        this.maxChunks = Math.max(maxChunks, 1);
    }

    /** 切片结果 */
    public record Chunk(String text, int index) {}

    public List<Chunk> chunk(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return List.of();
        }

        String text = rawText.strip();

        // 防止超大文件产生过多 chunk
        int maxTextLen = maxChunks * chunkSize;
        if (text.length() > maxTextLen) {
            log.warn("文本过长（{} 字符），截断至 {} 字符", text.length(), maxTextLen);
            text = text.substring(0, maxTextLen);
        }

        List<String> rawChunks = new ArrayList<>();
        for (String paragraph : PARAGRAPH_SPLIT.split(text)) {
            String para = paragraph.strip();
            if (para.isEmpty()) {
                continue;
            }
            if (para.length() <= chunkSize) {
                rawChunks.add(para);
                continue;
            }
            splitOversizedParagraph(para, rawChunks);
        }

        // 加相邻重叠（除第一个 chunk 外，前面拼上一块的尾部）
        List<Chunk> result = new ArrayList<>(rawChunks.size());
        for (int i = 0; i < rawChunks.size(); i++) {
            String chunk = rawChunks.get(i);
            if (i > 0 && overlap > 0) {
                String prev = rawChunks.get(i - 1);
                String overlapText = prev.length() > overlap
                        ? prev.substring(prev.length() - overlap)
                        : prev;
                chunk = overlapText + chunk;
            }
            result.add(new Chunk(chunk, i));
        }

        log.info("文本切片完成：{} 个 chunk（原文 {} 字符，chunkSize={}, overlap={}）",
                result.size(), text.length(), chunkSize, overlap);
        return result;
    }

    private void splitOversizedParagraph(String para, List<String> out) {
        StringBuilder current = new StringBuilder();
        for (String rawSentence : SENTENCE_SPLIT.split(para)) {
            String sentence = rawSentence.strip();
            if (sentence.isEmpty()) {
                continue;
            }
            if (current.length() + sentence.length() <= chunkSize) {
                current.append(sentence);
                continue;
            }

            if (current.length() > 0) {
                out.add(current.toString());
            }

            if (sentence.length() > chunkSize) {
                // 单句仍然超长：按字数硬切
                int step = Math.max(chunkSize - overlap, 1);
                for (int i = 0; i < sentence.length(); i += step) {
                    out.add(sentence.substring(i, Math.min(i + chunkSize, sentence.length())));
                }
                current.setLength(0);
            } else {
                current.setLength(0);
                current.append(sentence);
            }
        }
        if (current.length() > 0) {
            out.add(current.toString());
        }
    }
}
