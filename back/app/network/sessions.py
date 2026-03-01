import json
import uuid
from typing import Dict, Annotated, List, TypedDict
import logging
from langgraph.graph import add_messages

logging.basicConfig(
    level=logging.DEBUG,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)
logger.setLevel(logging.DEBUG)

class ConversationState(TypedDict):
    objective: str
    messages: Annotated[List, add_messages]
    llm_calls: int
    uuid: str  # Store the user's UUID to allow research on him

sessions: Dict[str, ConversationState] = {}

def generate_uuid():
    uuidG = str(uuid.uuid4())
    sessions[uuidG] = {"messages": [], "llm_calls": 0, "uuid": uuidG, "objective": ""}
    return uuidG

def add_session(uuid_str: str, agent):
    sessions[uuid_str] = {"agent": agent}

def get_session(uuid_str: str):
    return sessions[uuid_str]

def is_session_valid(uuid_str: str):
    is_valid = uuid_str in sessions
    return is_valid

def get_last_message(uuid_str: str):
    return sessions[uuid_str]["messages"][-1]

def get_next_action(uuid_str: str):
    last_message = next((m for m in reversed(sessions[uuid_str]) if m.type == "human"), None)
    if not last_message:
        return "clarify"
    words = len(str(last_message.content).split())
    return "answer" if words >= 3 else "clarify"