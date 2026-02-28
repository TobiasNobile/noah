import uuid
from typing import Dict, Annotated, List, TypedDict

from langgraph.graph import add_messages


class ConversationState(TypedDict):
    messages: Annotated[List, add_messages]

sessions = Dict[uuid.UUID, ConversationState]


def generate_uuid():
    uuidG = uuid.uuid4()
    sessions[uuidG] = None
    return uuid.uuid4()

def add_session(uuid, agent):
    sessions[uuid] = {"agent": agent}

def get_session(uuid):
    return sessions[uuid]

def is_session_valid(uuid):
    return uuid in sessions

def get_last_message(uuid):
    return sessions[uuid]["messages"][-1]

def get_next_action(uuid):
    last_message =  next((m for m in reversed(sessions[uuid]) if m.type == "human"), None)
    if not last_message:
        return "clarify"
    words = len(str(last_message.content).split())
    return "answer" if words >= 3 else "clarify"