from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
from back.agents import NavigationAgent

app = FastAPI()

sessions = {}

class ObjectifRequest(BaseModel):
    session_id: str
    objectif: str

class GPS(BaseModel):
    lat: float
    lon: float
    altitude: float
    accuracy: float

class Frame(BaseModel):
    session_id: str
    timestamp: str
    gps: GPS
    image_path: str

class QuestionRequest(BaseModel):
    session_id: str
    question: str

@app.post("/start")
def start_session(request: ObjectifRequest):
    """L'utilisateur définit son objectif au départ"""
    sessions[request.session_id] = {
        "agent": NavigationAgent(objectif=request.objectif),
        "last_frame": None
    }
    return {"status": "session créée", "session_id": request.session_id}

@app.post("/frame")
def receive_frame(request: Frame):
    """Reçoit une frame toutes les 30s, la stocke sans appeler le VLM"""
    if request.session_id not in sessions:
        raise HTTPException(status_code=404, detail="session inconnue")
    
    sessions[request.session_id]["last_frame"] = request.model_dump()
    return {"status": "frame reçue"}

@app.post("/ask")
def ask(request: QuestionRequest):
    """L'utilisateur pose une question, on appelle le VLM avec la dernière frame"""
    if request.session_id not in sessions:
        raise HTTPException(status_code=404, detail="session inconnue")
    
    session = sessions[request.session_id]

    if session["last_frame"] is None:
        raise HTTPException(status_code=400, detail="aucune frame reçue pour l'instant")
    
    answer = session["agent"].ask(request.question, session["last_frame"])
    return {"answer": answer}