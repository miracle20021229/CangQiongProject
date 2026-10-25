"""把 knowledge 中的 Markdown 原文转换为带来源的知识文档。

阅读 refund-policy.md 时关注资格、时限和例外；其余文件说明取消、配送、订阅及 FAQ。
这些文档提供问答依据，具体订单是否可操作仍由业务工具判断。
阅读提示留在代码中，避免混入后续检索的知识正文。
"""

from pathlib import Path

from app.rag.schemas import KnowledgeDocument


def load_markdown_documents(directory: Path) -> list[KnowledgeDocument]:
    documents: list[KnowledgeDocument] = []
    for path in sorted(directory.glob("*.md")):
        # 稳定的文件顺序便于重复导入；原文完整保留，交给后续步骤切分。
        content = path.read_text(encoding="utf-8")
        # 优先用一级标题；没有标题时从文件名生成。
        title = next(
            (
                line.removeprefix("# ").strip()
                for line in content.splitlines()
                if line.startswith("# ")
            ),
            path.stem.replace("-", " ").title(),
        )
        # 文件名生成文档 ID 和分类，source 保留可追溯的来源路径。
        documents.append(
            KnowledgeDocument(
                document_id=path.stem,
                title=title,
                category=path.stem.removesuffix("-policy").replace("-", "_"),
                source=str(path),
                content=content,
            )
        )
    return documents
