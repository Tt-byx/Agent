"""P9 · LangGraph 对照 demo：用「状态图编排」复刻 Java 侧手写的 ReAct 循环。

对照关系
========
Java 侧（backend-java/src/main/java/com/scenic/ai/agent/core/AgentLoop.java）是**手写 while 循环**：
    reason（模型决定） -> act（执行工具） -> observe（结果回灌） -> 直到模型不再要工具

Python 侧用 LangGraph 的 StateGraph 表达**同一个循环**：
    reason 节点 --条件边--> act 节点 --边--> 回到 reason
循环结构本身由图声明，循环的推进由框架驱动。

运行方式
========
    pip install langgraph langchain-openai     # 国内可用 -i https://mirrors.aliyun.com/pypi/simple/
    set SCENIC_LLM_API_KEY=sk-xxx
    python langgraph_demo.py
"""
import os
from typing import Annotated, TypedDict

from langchain_core.messages import HumanMessage, SystemMessage, ToolMessage
from langchain_core.tools import tool
from langchain_openai import ChatOpenAI
from langgraph.graph import END, START, StateGraph
from langgraph.graph.message import add_messages

API_KEY = os.environ.get("SCENIC_LLM_API_KEY", "")
BASE_URL = os.environ.get("SCENIC_LLM_BASE_URL", "https://api.deepseek.com/v1")
MODEL = os.environ.get("SCENIC_LLM_MODEL", "deepseek-flash")

SYSTEM = ("你是景区智能导游。用纯文本、口语化的中文回答，不要使用任何 Markdown 符号，"
          "控制在 3-5 句话。涉及票价计算和演出时间的问题请调用工具，不要凭记忆算。")

# ============ 工具：与 Java 侧 ScenicTools 是同一套业务逻辑 ============

TICKET_PRICE = {"成人": 210, "学生": 105, "老人": 105, "儿童": 0, "军人或残疾人": 0}


@tool
def calculate_ticket_price(visitor_type: str, count: int) -> str:
    """计算景区门票总价。visitor_type 可取：成人、学生、老人、儿童、军人或残疾人；count 为人数。"""
    price = TICKET_PRICE.get(visitor_type.strip(), 210)
    total = price * count
    return f"{visitor_type} {count} 人：单价 {price} 元，合计 {total} 元。另外网购联票 225 元含门票 + 观光车。"


SHOW_TIMES = {"九龙灌浴": ["10:00", "11:30", "13:30", "15:00"],
              "吉祥颂": ["10:35", "11:30", "14:00", "16:00"]}


@tool
def get_next_show_time(show_name: str) -> str:
    """查询演出下一场开始时间。show_name 可取：九龙灌浴 或 吉祥颂。"""
    import datetime
    times = SHOW_TIMES.get(show_name.strip())
    if not times:
        return f"没有找到「{show_name}」的演出信息。可查询：{list(SHOW_TIMES)}"
    now = datetime.datetime.now().strftime("%H:%M")
    nxt = next((t for t in times if t > now), None)
    if nxt is None:
        return f"{show_name} 今天的场次（{times}）都已结束，现在 {now}。"
    return f"{show_name} 今天场次 {times}；现在 {now}；下一场 {nxt}。"


TOOLS = [calculate_ticket_price, get_next_show_time]
llm = ChatOpenAI(model=MODEL, api_key=API_KEY, base_url=BASE_URL,
                 temperature=0.7).bind_tools(TOOLS)


# ============ 状态与节点 ============

class State(TypedDict):
    # add_messages 按 id 去重追加，这就是图里的"共享状态"
    messages: Annotated[list, add_messages]


def reason(state: State) -> dict:
    """reason：模型看当前对话，要么直接回答，要么发起工具调用。"""
    return {"messages": [llm.invoke([SystemMessage(content=SYSTEM)] + state["messages"])]}


def act(state: State) -> dict:
    """act + observe：执行模型要的工具，把结果作为 ToolMessage 回灌进状态。"""
    last = state["messages"][-1]
    by_name = {t.name: t for t in TOOLS}
    observations = []
    for call in last.tool_calls:
        fn = by_name.get(call["name"])
        if fn is None:
            result = f"错误：不存在名为「{call['name']}」的工具。可用：{list(by_name)}"
        else:
            result = fn.invoke(call["args"])
        observations.append(ToolMessage(content=str(result), tool_call_id=call["id"]))
    return {"messages": observations}


def route(state: State) -> str:
    """终止条件：模型不再要工具 -> 结束；否则 -> 执行工具。"""
    last = state["messages"][-1]
    return "act" if getattr(last, "tool_calls", None) else END


# ============ 组装状态图 ============

graph = StateGraph(State)
graph.add_node("reason", reason)
graph.add_node("act", act)
graph.add_edge(START, "reason")
graph.add_conditional_edges("reason", route)
graph.add_edge("act", "reason")          # 观察完回到 reason，形成循环
app = graph.compile()


def main() -> None:
    print("图结构（reason -> act -> reason ... -> END）：")
    print(app.get_graph().draw_ascii())

    questions = [
        "三个成人买门票一共多少钱？",
        "三个成人门票多少钱？另外九龙灌浴下一场几点？",
    ]
    for question in questions:
        print("\nQ:", question)
        result = app.invoke({"messages": [HumanMessage(content=question)]})
        print("A:", result["messages"][-1].content)
        tool_msgs = [m for m in result["messages"] if isinstance(m, ToolMessage)]
        print(f"   （本问共触发 {len(tool_msgs)} 次工具调用）")


if __name__ == "__main__":
    main()
