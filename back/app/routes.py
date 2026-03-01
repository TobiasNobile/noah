import logging
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
from langchain_core.messages import HumanMessage
from .network import sessions
from .network.sessions import is_session_valid
from .agents.MainAgent import MainAgent
from enum import Enum

# Configure logging
logger = logging.getLogger(__name__)
agent = MainAgent()

app = FastAPI()

class BaseRequest(BaseModel):
    uuid: str | None = None
    question: str

class ResultType(Enum):
    SUCCESS = "success"
    REGISTERED = "registered"
    ERROR = "error"

@app.post("/ask")
def main_endpoint(request: BaseRequest):

    generated_uuid_id = None

    if request.uuid is None: #Generate session
        generated_uuid_id = sessions.generate_uuid()
    elif not is_session_valid(request.uuid): #Invalid session
        raise HTTPException(status_code=404, detail="session not found")

    try:
        # Invoke the agent with the user's question
        result = agent.agent.invoke({
            "messages": [HumanMessage(content=request.question)]
        })

        # Extract the final answer from the agent's messages
        final_message = result["messages"][-1]
        answer = final_message.content if hasattr(final_message, 'content') else str(final_message)

        if generated_uuid_id is not None:
            return {"uuid": generated_uuid_id, "answer": answer, "type": ResultType.REGISTERED.value}
        else:
            return {"answer": answer, "type": ResultType.SUCCESS.value}
    except Exception as e:
        logger.error(f"Error processing request: {str(e)}")
        return {"type": ResultType.ERROR.value, "error": str(e)}