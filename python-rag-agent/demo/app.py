"""Streamlit demo:AI 面试陪练。

一页 demo:
- 输入问题 -> 看检索来源 + 流式答案
- 选 topic -> 出题 -> 写回答 -> 看评分(LLM-judge)
- 一键跑 Agent 模拟面试(SSE 流式)

启动:
  uv run streamlit run demo/app.py
  (需后端先起:uv run uvicorn app.main:app --reload)
  或 Docker:见 Dockerfile,同容器跑前后端
"""

from __future__ import annotations

import json

import httpx
import streamlit as st

BACKEND = st.secrets.get("backend_url", "http://localhost:8000")

st.set_page_config(page_title="AI 面试陪练", page_icon="🎯", layout="wide")
st.title("AI 面试陪练 · Python 版")
st.caption("RAG 知识问答 + LLM-judge 评估 + LangGraph Agent。后端同仓库 `python-rag-agent`。")

tab1, tab2, tab3 = st.tabs(["💬 RAG 问答", "📝 出题与评估", "🤖 Agent 模拟面试"])


# ---------- Tab 1: RAG 问答 ----------
with tab1:
    st.subheader("基于面试知识库的 RAG 问答")
    q = st.text_input("问个面试题", value="volatile 保证原子性吗?为什么?", key="rag_q")
    mode = st.selectbox("检索模式", ["hybrid", "vector", "hybrid_rerank"], key="rag_mode")
    if st.button("提问", key="rag_ask"):
        if not q.strip():
            st.warning("请输入问题")
        else:
            with st.spinner("检索 + 生成中..."):
                try:
                    r = httpx.post(
                        f"{BACKEND}/api/ask",
                        json={"question": q, "top_k": 4},
                        params={"mode": mode},
                        timeout=60.0,
                    )
                    data = r.json()
                    if data.get("code") != 0:
                        st.error(f"失败:{data.get('message')}")
                    else:
                        d = data["data"]
                        st.markdown("#### 答案")
                        st.markdown(d["answer"])
                        st.markdown("#### 来源")
                        for s in d.get("sources", []):
                            st.markdown(f"- `{s['id']}` · {s['topic']} · score={s['score']} · {s['card_type']}")
                        st.caption(f"usage: {d.get('usage', {})}")
                except Exception as e:
                    st.error(f"请求失败:{e}")


# ---------- Tab 2: 出题与评估 ----------
with tab2:
    st.subheader("出题 + LLM-as-judge 评估(温度 0,可复现)")
    topic = st.text_input("topic id", value="java.concurrency.volatile", key="ev_topic")
    col1, col2 = st.columns([1, 2])
    with col1:
        if st.button("出题", key="ev_q"):
            r = httpx.post(
                f"{BACKEND}/api/interview/question",
                json={"topic": topic, "count": 1},
                timeout=30.0,
            )
            d = r.json()
            if d.get("code") == 0 and d["data"]["questions"]:
                st.session_state["cur_q"] = d["data"]["questions"][0]
            else:
                st.error(d.get("message", "出题失败"))

    cur = st.session_state.get("cur_q")
    if cur:
        st.info(f"**{cur['question_id']}**(难度 {cur['difficulty']}):{cur['prompt']}")
        ans = st.text_area("你的回答", height=150, key="ev_ans")
        if st.button("评估", key="ev_eval") and ans.strip():
            with st.spinner("LLM-judge 评估中(温度 0)..."):
                r = httpx.post(
                    f"{BACKEND}/api/interview/evaluate",
                    json={"question_id": cur["question_id"], "user_answer": ans},
                    timeout=60.0,
                )
                d = r.json()
                if d.get("code") == 0:
                    ev = d["data"]["evaluation"]
                    c1, c2 = st.columns(2)
                    c1.metric("总分", f"{ev['score']}/100")
                    c2.metric("降级", "是" if ev.get("degraded") else "否")
                    if ev.get("dimension_scores"):
                        st.markdown("**维度分**")
                        st.json(ev["dimension_scores"])
                    st.markdown(f"**命中必答点**:{ev.get('hit', [])}")
                    st.markdown(f"**遗漏**:{ev.get('missed', [])}")
                    st.markdown(f"**常见错误**:{ev.get('mistakes', [])}")
                    st.markdown(f"**反馈**:{ev.get('feedback', '')}")
                else:
                    st.error(d.get("message"))


# ---------- Tab 3: Agent 模拟面试 ----------
with tab3:
    st.subheader("Agent 自动模拟面试(SSE 流式)")
    st.caption("retrieve -> question -> answer -> evaluate -> (followup) -> advise -> done")
    a_topic = st.text_input("topic", value="java.concurrency.volatile", key="ag_topic")
    rounds = st.slider("轮数", 1, 3, 1, key="ag_rounds")
    if st.button("开跑", key="ag_run"):
        st.info(f"Agent 模拟面试:{a_topic},轮数 {rounds}")
        placeholder = st.container()
        try:
            with httpx.stream(
                "POST",
                f"{BACKEND}/api/agent/session",
                json={"topic": a_topic, "rounds": rounds},
                timeout=120.0,
            ) as resp:
                cur_event = ""
                for line in resp.iter_lines():
                    if line.startswith("event:"):
                        cur_event = line.split(":", 1)[1].strip()
                    elif line.startswith("data:"):
                        raw = line.split(":", 1)[1].strip()
                        if raw == "[DONE]":
                            st.success("完成")
                            break
                        try:
                            payload = json.loads(raw)
                        except json.JSONDecodeError:
                            continue
                        with placeholder:
                            _render_agent_event(cur_event, payload)
        except Exception as e:
            st.error(f"Agent 失败:{e}")


def _render_agent_event(evt: str, payload):
    if evt == "retrieve":
        st.markdown(f"🔍 **检索**:tool_call=`{payload.get('tool_call', {}).get('name')}`,命中 {payload.get('docs_count', 0)} 条")
    elif evt == "question":
        st.markdown(f"❓ **出题(轮 {payload.get('round')})**:{payload.get('prompt')}")
    elif evt == "answer":
        st.markdown(f"💬 **模拟回答**:{payload.get('text', '')[:200]}...")
    elif evt == "evaluate":
        st.markdown(f"✅ **评估**:score={payload.get('score')}, missed={payload.get('missed')}")
    elif evt == "followup":
        st.warning(f"🔁 **追问**:{payload.get('reason')}")
    elif evt == "advise":
        st.markdown("📚 **学习建议**")
        st.markdown(payload.get("advice", ""))
    elif evt == "done":
        st.success(f"🎉 完成,共 {payload.get('rounds_done', 0)} 轮,工具调用 {len(payload.get('tool_calls', []))} 次")
    elif evt == "error":
        st.error(f"错误:{payload}")
