"""面试业务子包:出题(question_gen)+ LLM-as-judge 评估(evaluator)。

两项能力:
  generate_questions  出题:直接返回 topic 的人工撰写 recallPrompts(保证题目质量);
  evaluate_answer     评估:按 topic 的 rubric(必答点/常见错误/维度权重)用
                      LLM-as-judge 打分,temperature=0 保证可复现,与「面试智练」App 同源。

两者都依赖 rag/loader 的 KnowledgeBase 读取 topic 元数据,不自己存储题目/标准。
"""

from app.interview.evaluator import evaluate_answer
from app.interview.question_gen import generate_questions

__all__ = ["generate_questions", "evaluate_answer"]
