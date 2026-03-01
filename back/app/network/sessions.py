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
    """
    Initialize a new session for a user with the given UUID and agent information.
    :param uuid_str: The unique identifier for the user's session.
    :param agent: The agent information to be associated with the session, which may include details about the user's preferences, history, or any relevant context needed for the session.
    :return: None
    """
    sessions[uuid_str] = {"agent": agent}

def get_session(uuid_str: str):
    """
    Retrieve the session information for a given UUID.
    :param uuid_str: The unique identifier for the user's session.
    :return: The session information associated with the provided UUID, which may include details about the agent, messages, and any other relevant context stored in the session.
    """
    return sessions[uuid_str]

def is_session_valid(uuid_str: str):
    """
    Check if a session with the given UUID exists and is valid.
    :param uuid_str: The unique identifier for the user's session to be validated.
    :return: A boolean value indicating whether the session with the provided UUID exists in the sessions dictionary, which implies that it is valid and can be used for further interactions.
    """
    is_valid = uuid_str in sessions
    return is_valid

def get_last_message(uuid_str: str):
    """
    Retrieve the last message from the session associated with the given UUID.
    :param uuid_str: The unique identifier for the user's session from which to retrieve the last message.
    :return: The last message in the session's message history, which may include the content of the message, its type (e.g., human or agent), and any other relevant information stored in the session's messages list. If there are no messages in the session, this function may return None or an appropriate indication that there are no messages available.
    """
    return sessions[uuid_str]["messages"][-1]

def get_next_action(uuid_str: str):
    """
    Determine the next action to take based on the last message in the session associated with the given UUID. This function analyzes the content of the last message, particularly if it is from a human, and decides whether to provide an answer or ask for clarification based on the number of words in the message. If there is no human message found, it defaults to asking for clarification.
    :param uuid_str: The unique identifier for the user's session from which to determine the next action based on the last message.
    :return: A string indicating the next action to take, which can be either "answer" if the last human message contains three or more words, or "clarify" if it contains fewer than three words or if there is no human message found in the session's message history. This function helps guide the flow of the conversation by determining whether to provide a response or seek further clarification from the user based on their last input.
    """
    last_message = next((m for m in reversed(sessions[uuid_str]) if m.type == "human"), None)
    if not last_message:
        return "clarify"
    words = len(str(last_message.content).split())
    return "answer" if words >= 3 else "clarify"