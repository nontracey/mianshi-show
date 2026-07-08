"""Dockerfile for python-rag-agent(FastAPI 后端 + Streamlit demo)。

HF Spaces 或 Render 部署用。机密走环境变量(Space Secrets),不入镜像。
"""

FROM python:3.11-slim

# 系统依赖
RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential curl && \
    rm -rf /var/lib/apt/lists/*

# 安装 uv(快速依赖管理)
RUN pip install --no-cache-dir uv

WORKDIR /app

# 先拷依赖清单,利用 Docker 层缓存
COPY pyproject.toml ./
COPY app/ ./app/
COPY demo/ ./demo/
COPY tests/ ./tests/
COPY benchmarks/ ./benchmarks/
COPY data/ ./data/
COPY README.md ./

# 安装核心依赖 + demo(streamlit)+ dev(pytest 用于健康检查)
RUN uv sync --extra demo || uv pip install streamlit httpx

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    APP_HOST=0.0.0.0 \
    APP_PORT=8000

# HF Spaces 默认暴露 7860;同时暴露 8501(streamlit)
EXPOSE 7860 8000 8501

# 启动脚本:后端 uvicorn + 前端 streamlit
# HF Spaces 用 7860 端口,Streamlit 用 7860;后端用 8000(内部)
CMD ["sh", "-c", "uv run uvicorn app.main:app --host 0.0.0.0 --port 8000 & uv run streamlit run demo/app.py --server.port 7860 --server.address 0.0.0.0"]
