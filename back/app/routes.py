import base64
import logging
from fastapi import FastAPI
from pydantic import BaseModel
from langchain_core.messages import HumanMessage
from .network import sessions
from .network.sessions import is_session_valid
from .agents.MainAgent import MainAgent
from .tools.userInfo_tool import UserInfo, update_user_info
from .utils.audio_processor import AudioProcessor
from enum import Enum
import os

# Configure logging with DEBUG level
logging.basicConfig(
    level=logging.DEBUG,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)
logger.setLevel(logging.DEBUG)

agent = MainAgent()

app = FastAPI()
dirname = os.path.dirname(__file__)

# Store audio processors per session for chunk accumulation
audio_processors: dict[str, AudioProcessor] = {}

def get_audio_processor(uuid: str) -> AudioProcessor:
    """Get or create an audio processor for a session."""
    if uuid not in audio_processors:
        audio_processors[uuid] = AudioProcessor()
    return audio_processors[uuid]

class MainEndpointBase(BaseModel):
    uuid: str
    question: str

class SpeechEndpointBase(BaseModel):
    uuid: str
    audio_data: str  # Base64 encoded audio data

class EndSpeechEndpointBase(BaseModel):
    uuid: str

class ImageEndpointBase(BaseModel):
    uuid: str
    image_data: str  # Base64 encoded image data

class DataEndpointBase(BaseModel):
    uuid: str
    user_info: UserInfo

class ResultType(Enum):
    SUCCESS = "success"
    REGISTERED = "registered"
    OTHER_ERROR = "other_error"
    KEY_ERROR = "key_error"

@app.post("/register")
def register_endpoint():
    generated_uuid_id = sessions.generate_uuid()
    logger.debug(f"Generated new session UUID: {generated_uuid_id}")
    return {"uuid": generated_uuid_id, "type": ResultType.REGISTERED.value}



@app.post("/data")
def data_endpoint(request: DataEndpointBase):
    if not is_session_valid(request.uuid):
        logger.warning(f"Invalid session UUID: {request.uuid}")
        return {"type": ResultType.KEY_ERROR.value, "error": "Session not found"}
    try:
        logger.debug(f"Received user info for session {request.uuid}: {request.user_info}")
        update_user_info(request.uuid, request.user_info)
        return {"type": ResultType.SUCCESS.value}
    except Exception as e:
        logger.error(f"Error processing user info: {str(e)}")
        return {"type": ResultType.OTHER_ERROR.value, "error": str(e)}


@app.post("/audio/chunk")
def audio_chunk_endpoint(request: SpeechEndpointBase):
    """
    Receive a single audio chunk and accumulate it.
    This endpoint is called multiple times as audio is recorded.
    """
    if not is_session_valid(request.uuid):
        logger.warning(f"Invalid session UUID: {request.uuid}")
        return {"type": ResultType.KEY_ERROR.value, "error": "Session not found"}

    try:
        # Decode base64 audio chunk
        audio_chunk = base64.b64decode(request.audio_data)

        # Add chunk to processor
        processor = get_audio_processor(request.uuid)
        processor.add_chunk(audio_chunk)

        # Get stats
        stats = processor.get_stats()
        logger.debug(f"Audio chunk received for session {request.uuid}: {len(audio_chunk)} bytes. "
                    f"Stats: {stats}")

        return {
            "type": ResultType.SUCCESS.value,
            "chunk_size": len(audio_chunk),
            "total_bytes": stats["total_bytes"],
            "duration_seconds": stats["duration_seconds"]
        }
    except Exception as e:
        logger.error(f"Error processing audio chunk: {str(e)}")
        return {"type": ResultType.OTHER_ERROR.value, "error": str(e)}


@app.post("/audio/finish")
def audio_finish_endpoint(request: EndSpeechEndpointBase):
    """
    Called when audio recording is complete.
    Converts accumulated chunks to WAV and saves to disk.
    """
    if not is_session_valid(request.uuid):
        logger.warning(f"Invalid session UUID: {request.uuid}")
        return {"type": ResultType.KEY_ERROR.value, "error": "Session not found"}

    try:
        processor = get_audio_processor(request.uuid)
        stats = processor.get_stats()

        if stats["total_bytes"] == 0:
            logger.warning(f"No audio data accumulated for session {request.uuid}")
            return {"type": ResultType.OTHER_ERROR.value, "error": "No audio data recorded"}

        # Convert to WAV format
        wav_data = processor.to_wav_bytes()

        # Create directory if it doesn't exist
        audio_dir = os.path.join(dirname, f"temp/cache/audio/{request.uuid}")

        numberOfFilesInDir = 0

        if not os.path.exists(audio_dir):
            os.makedirs(audio_dir)
        else:
            numberOfFilesInDir = len(os.listdir(audio_dir))

        logger.debug("Saving at audio directory: " + audio_dir)


        # Save WAV file
        path = os.path.join(audio_dir, f"{numberOfFilesInDir}.wav")
        with open(path, "wb") as f:
            f.write(wav_data)

        logger.info(f"Audio file saved for session {request.uuid}: {path} ({len(wav_data)} bytes, {stats['duration_seconds']:.2f}s)")

        # Clear the processor for next recording
        processor.clear()

        return {
            "type": ResultType.SUCCESS.value,
            "file_path": path,
            "file_size": len(wav_data),
            "duration_seconds": stats["duration_seconds"],
            "audio_stats": stats
        }
    except Exception as e:
        logger.error(f"Error finalizing audio: {str(e)}")
        return {"type": ResultType.OTHER_ERROR.value, "error": str(e)}

@app.post("/ask")
def main_endpoint(request: MainEndpointBase):

    if not is_session_valid(request.uuid): #Invalid session
        logger.warning(f"Invalid session UUID: {request.uuid}")
        return {"type": ResultType.KEY_ERROR.value}
    else:
        logger.debug(f"Using existing session UUID: {request.uuid}")

    try:
        # Invoke the agent with the user's question
        logger.debug(f"Invoking agent for session {request.uuid} with question: {request.question}")
        result = agent.agent.invoke({
            "messages": [HumanMessage(content=request.question)],
            "uuid": request.uuid
        })

        # Extract the final answer from the agent's messages
        final_message = result["messages"][-1]
        answer = final_message.content if hasattr(final_message, 'content') else str(final_message)

        return {"answer": answer, "type": ResultType.SUCCESS.value}
    except Exception as e:
        logger.error(f"Error processing request: {str(e)}")
        return {"type": ResultType.OTHER_ERROR.value, "error": str(e)}


@app.post("/image")
def image_endpoint(request: ImageEndpointBase):
    if not is_session_valid(request.uuid):
        logger.debug(f"Invalid session UUID: {request.uuid}")
        return {"type": ResultType.KEY_ERROR.value}

    try:
        logger.debug(f"Received image data for session {request.uuid}: {request.image_data[:30]}...")

        image_dir = os.path.join(dirname, f"temp/cache/image/{request.uuid}")

        numberOfFilesInDir = 0

        if not os.path.exists(image_dir):
            os.makedirs(image_dir)
        else:
            numberOfFilesInDir = len(os.listdir(image_dir))

        logger.debug(f"Writing image data to {image_dir}")
        dataDecoded = base64.b64decode(request.image_data)
        with open(f"{numberOfFilesInDir}.jpeg", "wb") as f:
            f.write(dataDecoded)

        return {"type": ResultType.SUCCESS.value}
    except Exception as e:
        logger.error(f"Error processing image: {str(e)}")
        return {"type": ResultType.OTHER_ERROR.value, "error": str(e)}