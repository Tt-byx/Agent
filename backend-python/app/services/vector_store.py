import logging
import chromadb
from app.core.config import settings

logger = logging.getLogger(__name__)

_client: chromadb.ClientAPI | None = None
_collection = None

COLLECTION_NAME = "scenic_knowledge"


def get_client() -> chromadb.ClientAPI:
    """获取 ChromaDB 持久化客户端（单例）"""
    global _client
    if _client is None:
        _client = chromadb.PersistentClient(path=settings.chroma_persist_dir)
        logger.info(f"ChromaDB 客户端初始化完成，持久化目录: {settings.chroma_persist_dir}")
    return _client


def get_collection():
    """获取或创建 scenic_knowledge 集合"""
    global _collection
    if _collection is None:
        client = get_client()
        _collection = client.get_or_create_collection(
            name=COLLECTION_NAME,
            metadata={"hnsw:space": "cosine"},
        )
        logger.info(f"ChromaDB 集合 '{COLLECTION_NAME}' 就绪，当前文档数: {_collection.count()}")
    return _collection


def add_document_chunks(
    doc_id: int,
    doc_title: str,
    chunks: list[dict],
    embeddings: list[list[float]],
):
    """将文档的 chunk 向量存入 ChromaDB"""
    collection = get_collection()
    ids = [f"doc_{doc_id}_chunk_{c['index']}" for c in chunks]
    documents = [c["text"] for c in chunks]
    metadatas = [
        {"doc_id": doc_id, "doc_title": doc_title, "chunk_index": c["index"]}
        for c in chunks
    ]

    collection.add(
        ids=ids,
        documents=documents,
        embeddings=embeddings,
        metadatas=metadatas,
    )
    logger.info(f"文档 '{doc_title}' (id={doc_id}) 已存入 {len(chunks)} 个 chunk")


def delete_document(doc_id: int):
    """删除指定文档的所有向量"""
    collection = get_collection()
    collection.delete(where={"doc_id": doc_id})
    logger.info(f"已删除文档 id={doc_id} 的所有向量")


def query(
    query_embedding: list[float],
    top_k: int = 5,
) -> list[dict]:
    """检索最相关的 chunk"""
    collection = get_collection()
    if collection.count() == 0:
        return []

    results = collection.query(
        query_embeddings=[query_embedding],
        n_results=min(top_k, collection.count()),
        include=["documents", "metadatas", "distances"],
    )

    chunks = []
    for doc, meta, dist in zip(
        results["documents"][0],
        results["metadatas"][0],
        results["distances"][0],
    ):
        chunks.append({
            "text": doc,
            "doc_title": meta.get("doc_title", ""),
            "distance": dist,
        })

    return chunks
