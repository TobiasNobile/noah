import os
import logging
from typing import Literal

from langchain_core.messages import SystemMessage, ToolMessage
from langchain_mistralai import ChatMistralAI
from langgraph.constants import END, START
from langgraph.graph import StateGraph
from mistralai import Mistral
from dotenv import load_dotenv

from ..config.config import MODEL, INITIAL_PROMPT
from ..network.sessions import ConversationState
from ..tools.tools import tools, tools_by_name

# Configure logging
logger = logging.getLogger(__name__)
logger.setLevel(logging.DEBUG)

load_dotenv()

client = Mistral(api_key=os.environ["MISTRAL_API_KEY"])

llm = ChatMistralAI(model = MODEL, temperature=0.1)
llm_with_tools = llm.bind_tools(tools)

class MainAgent:
    def __init__(self):
        agent_builder = StateGraph(ConversationState)
        agent_builder.add_node("llm_call", self.llm_call)
        agent_builder.add_node("tool_node", self.tool_node)

        agent_builder.add_edge(START, "llm_call")
        agent_builder.add_conditional_edges(
            "llm_call",
            self.should_continue,
            {
                "tool_node": "tool_node",
                END: END
            }
        )
        agent_builder.add_edge("tool_node", "llm_call")

        self.agent = agent_builder.compile()

    def generate_graph_image(self):
        """
        Generates a visual representation of the agent's state graph and saves it as an image file. The function first attempts to create a PNG image using Mermaid syntax. If that fails, it falls back to saving the graph in Mermaid format as a .mmd file. The generated graph illustrates the flow of states and transitions within the agent's decision-making process.
        :return: None
        """
        try:
            graph_image = self.agent.get_graph(xray=True).draw_mermaid_png()
            with open("mainAgent_graph.png", "wb") as f:
                f.write(graph_image)
            print("Graph saved to mainAgent_graph.png")
        except Exception as e:
            print(f"Could not save graph image: {e}")
            try:
                mermaid_graph = self.agent.get_graph(xray=True).draw_mermaid()
                with open("mainAgent_graph.mmd", "w") as f:
                    f.write(mermaid_graph)
                print("Graph saved to mainAgent_graph.mmd")
            except Exception as e2:
                print(f"Could not save graph: {e2}")

    def llm_call(self, state: ConversationState):
        """LLM decides whether to call a tool or not"""

        # Get UUID from state
        user_uuid = state.get('uuid', 'unknown')

        # Create system message with UUID
        system_message = SystemMessage(
            content=INITIAL_PROMPT.format(objective=state.get('objective', "User statement unclear"), uuid=user_uuid)
        )

        return {
            "messages": [
                llm_with_tools.invoke(
                    [system_message] + state["messages"]
                )
            ],
            "llm_calls": state.get('llm_calls', 0) + 1,
            "uuid": state.get('uuid'),
            "objective": state.get('objective', "User statement unclear")
        }

    def tool_node(self, state: ConversationState):
        """Performs the tool call"""

        result = []
        for tool_call in state["messages"][-1].tool_calls:
            tool = tools_by_name[tool_call["name"]]
            observation = tool.invoke(tool_call["args"])
            result.append(ToolMessage(content=observation, tool_call_id=tool_call["id"]))

        return {
            "messages": result,
            "uuid": state.get('uuid')
        }

    def should_continue(self, state: ConversationState) -> Literal["tool_node", END]:
        """Decide if we should continue the loop or stop based upon whether the LLM made a tool call"""

        messages = state["messages"]
        last_message = messages[-1]

        # If the LLM makes a tool call, then perform an action
        if last_message.tool_calls:
            return "tool_node"

        # Otherwise, we stop (reply to the user)
        return END
