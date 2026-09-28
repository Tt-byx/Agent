from pydantic import BaseModel, Field


class ProcessRequest(BaseModel):
    doc_id: int = Field(..., description="文档ID")
    file_path: str = Field(..., description="文件在服务器上的路径")
    file_type: str = Field(..., description="文件类型: docx/xlsx")
    doc_title: str = Field("", description="文档标题")


class ProcessResponse(BaseModel):
    doc_id: int
    chunk_count: int
    status: str
    message: str = ""
