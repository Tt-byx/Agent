package com.scenic.ai.rag.ingest;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 切分器单元测试 —— 不依赖任何外部服务，可在 pgvector / embedding 未就绪时独立验证。
 */
class DocumentChunkerTest {

    private DocumentChunker chunker(int size, int overlap) {
        return new DocumentChunker(size, overlap, 2000);
    }

    @Test
    void blankTextYieldsNoChunks() {
        assertThat(chunker(10, 3).chunk(null)).isEmpty();
        assertThat(chunker(10, 3).chunk("   ")).isEmpty();
        assertThat(chunker(10, 3).chunk("\n\n\n")).isEmpty();
    }

    @Test
    void shortParagraphBecomesSingleChunkWithoutOverlap() {
        List<DocumentChunker.Chunk> chunks = chunker(100, 10).chunk("灵隐寺始建于东晋。");

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).text()).isEqualTo("灵隐寺始建于东晋。");
        assertThat(chunks.get(0).index()).isZero();
    }

    @Test
    void secondChunkIsPrefixedWithPreviousTail() {
        List<DocumentChunker.Chunk> chunks = chunker(10, 3).chunk("AAAAAA\n\nBBBBBB");

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).text()).isEqualTo("AAAAAA");
        // 上一块尾部 3 字 + 本块内容
        assertThat(chunks.get(1).text()).isEqualTo("AAABBBBBB");
        assertThat(chunks.get(1).index()).isEqualTo(1);
    }

    @Test
    void oversizedParagraphIsSplitOnSentenceBoundary() {
        List<DocumentChunker.Chunk> chunks = chunker(12, 0).chunk("第一句。第二句。第三句。第四句。");

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).text()).isEqualTo("第一句。第二句。第三句。");
        assertThat(chunks.get(1).text()).isEqualTo("第四句。");
    }

    @Test
    void singleOverlongSentenceIsHardSplit() {
        String overlongSentence = "甲".repeat(25) + "。";

        List<DocumentChunker.Chunk> chunks = chunker(10, 2).chunk(overlongSentence);

        // 26 字 / 步长 8 → 至少 4 块
        assertThat(chunks.size()).isGreaterThanOrEqualTo(3);
        assertThat(chunks.get(0).text()).hasSize(10);
    }

    @Test
    void textIsTruncatedWhenExceedingMaxChunksBudget() {
        DocumentChunker limited = new DocumentChunker(5, 0, 2); // 预算 = 10 字符

        List<DocumentChunker.Chunk> chunks = limited.chunk("甲".repeat(100));

        assertThat(chunks).hasSizeLessThanOrEqualTo(2);
    }
}
