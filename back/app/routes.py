import logging
from typing import TypedDict, Annotated, List

from fastapi import FastAPI, HTTPException
from langgraph.graph import add_messages
from pydantic import BaseModel
import network.sessions
from .network.sessions import sessions, is_session_valid
from enum import Enum

# Configure logging
logger = logging.getLogger(__name__)

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

    if request.session_id is None: #Generate session
        generated_uuid_id = network.sessions.generate_uuid()
    elif not is_session_valid(request.session_id): #Invalid session
        raise HTTPException(status_code=404, detail="session not found")


    # answer = agent.ask(request.question)
    #
    # if generated_uuid_id is not None:
    #     return {"uuid": generated_uuid_id, "type": ResultType.REGISTERED.value}
    # else:
    #     if answer is None:
    #         return {"type": ResultType.ERROR.value}
    #     else :
    #         return {"answer": answer, "type": ResultType.SUCCESS.value}


#
# class ObjectifRequest(BaseModel):
#     session_id: str
#     objectif: str
#
# class GPS(BaseModel):
#     lat: float
#     lon: float
#     altitude: float
#     accuracy: float
#
# class Frame(BaseModel):
#     session_id: str
#     timestamp: str
#     gps: GPS
#     image_path: str
#
# class QuestionRequest(BaseModel):
#     session_id: str
#     question: str
#
# @app.post("/start")
# def start_session(request: ObjectifRequest):
#     """L'utilisateur définit son objectif au départ"""
#     logger.info(f"Got: {request.objectif}")
#     sessions[request.session_id] = {
#         "agent": NavigationAgent(objectif=request.objectif),
#         "last_frame": None
#     }
#     return {"status": "session créée", "session_id": request.session_id}
#
# @app.post("/frame")
# def receive_frame(request: Frame):
#     """Reçoit une frame toutes les 30s, la stocke sans appeler le VLM"""
#     if request.session_id not in sessions:
#         raise HTTPException(status_code=404, detail="session inconnue")
#
#     sessions[request.session_id]["last_frame"] = request.model_dump()
#     return {"status": "frame reçue"}
#
# @app.post("/ask")
# def ask(request: QuestionRequest):
#     """L'utilisateur pose une question, on appelle le VLM avec la dernière frame"""
#     if request.session_id not in sessions:
#         raise HTTPException(status_code=404, detail="session inconnue")
#
#     session = sessions[request.session_id]
#
#     if session["last_frame"] is None:
#         raise HTTPException(status_code=400, detail="aucune frame reçue pour l'instant")
#
#     answer = session["agent"].ask(request.question, session["last_frame"])
#     return {"answer": answer}