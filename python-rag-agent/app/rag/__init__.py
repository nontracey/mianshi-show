"""RAG 链路子包:loader -> splitter -> embedder -> store -> retriever -> generator。

这是整个服务"检索增强生成"能力的核心。六个环节各司其职:
  loader    从三层数据源(本地 clone / 远程 manifest / 样例)加载知识库 topic
  splitter  按卡片类型把 learningCards 切成带 metadata 的 chunk
  embedder  把文本转成向量(支持 api / local / fastembed 三种后端)
  store     向量库抽象(memory / chroma / pgvector)
  retriever 混合检索:向量 + BM25 -> RRF 融合 -> (可选)Rerank
  generator 拼防幻觉 System Prompt 调 LLM 生成答案并抽取来源

数据流:入库走 loader->splitter->embedder->store(见 api/rag.py ingest);
查询走 embedder->retriever->generator(见 api/rag.py ask)。
agent 与 interview 模块通过 loader.get_kb() 读取 topic 元数据(出题/评估用)。

M1 阶段只启用 loader(供出题/评估读 topic 元数据);M2 起完整启用。
"""
