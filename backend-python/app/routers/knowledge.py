import json
import logging
from fastapi import APIRouter, HTTPException
from fastapi.responses import StreamingResponse
from app.schemas.knowledge import ProcessRequest
from app.services.document_parser import parse_file
from app.services.text_chunker import chunk_text
from app.services.embedding_service import embed_texts
from app.services.vector_store import add_document_chunks, delete_document

logger = logging.getLogger(__name__)
router = APIRouter()


def _sse_event(event_type: str, data: dict) -> str:
    return f"event: {event_type}\ndata: {json.dumps(data, ensure_ascii=False)}\n\n"


@router.post("/knowledge/process")
async def process_document(request: ProcessRequest):
    """解析文档 → 切片 → 向量化 → 存入 ChromaDB（SSE 流式返回进度）"""

    async def event_generator():
        try:
            # 阶段1: 解析文档
            yield _sse_event("progress", {
                "stage": "parsing", "progress": 5, "message": "正在解析文档..."
            })

            text = parse_file(request.file_path, request.file_type)
            if not text.strip():
                yield _sse_event("error", {"message": "文档解析结果为空"})
                return

            yield _sse_event("progress", {
                "stage": "parsing", "progress": 20, "message": f"文档解析完成 ({len(text)} 字符)"
            })

            # 阶段2: 文本切片
            yield _sse_event("progress", {
                "stage": "chunking", "progress": 25, "message": "正在切分文本..."
            })

            chunks = chunk_text(text)
            if not chunks:
                yield _sse_event("error", {"message": "文本切片结果为空"})
                return

            chunk_count = len(chunks)
            yield _sse_event("progress", {
                "stage": "chunking", "progress": 30,
                "message": f"文本切分完成 ({chunk_count} 个片段)"
            })

            # 阶段3: 向量化（逐批次报告进度）
            yield _sse_event("progress", {
                "stage": "embedding", "progress": 35,
                "message": f"开始向量化 (0/{chunk_count})..."
            })

            # 分批 embed 并报告进度
            BATCH_SIZE = 32
            all_embeddings = []
            texts_to_embed = [c["text"] for c in chunks]

            for i in range(0, len(texts_to_embed), BATCH_SIZE):
                batch = texts_to_embed[i:i + BATCH_SIZE]
                batch_embeddings = await embed_texts(batch)
                all_embeddings.extend(batch_embeddings)

                done = min(i + BATCH_SIZE, chunk_count)
                progress = 35 + int(55 * done / chunk_count)  # 35% ~ 90%
                yield _sse_event("progress", {
                    "stage": "embedding", "progress": progress,
                    "message": f"正在向量化 ({done}/{chunk_count})..."
                })

            # 阶段4: 存入向量数据库
            yield _sse_event("progress", {
                "stage": "storing", "progress": 92,
                "message": "正在存入向量数据库..."
            })

            add_document_chunks(request.doc_id, request.doc_title, chunks, all_embeddings)

            yield _sse_event("progress", {
                "stage": "storing", "progress": 100,
                "message": "存入完成"
            })

            # 完成
            yield _sse_event("done", {
                "chunk_count": chunk_count,
                "message": f"处理完成，共 {chunk_count} 个片段"
            })

            logger.info(f"文档处理完成: id={request.doc_id}, chunk_count={chunk_count}")

        except Exception as e:
            logger.error(f"文档处理失败: {e}")
            yield _sse_event("error", {"message": f"处理失败: {str(e)}"})

    return StreamingResponse(
        event_generator(),
        media_type="text/event-stream; charset=utf-8",
        headers={
            "Cache-Control": "no-cache",
            "X-Accel-Buffering": "no",
        },
    )


@router.delete("/knowledge/{doc_id}")
async def delete_document_vectors(doc_id: int):
    """删除指定文档的所有向量"""
    try:
        delete_document(doc_id)
        return {"status": "success", "message": f"文档 {doc_id} 的向量已删除"}
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"删除失败: {str(e)}")
