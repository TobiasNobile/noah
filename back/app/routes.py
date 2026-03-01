import base64
from fastapi import FastAPI
from langchain_core.messages import HumanMessage
from . import logger
from .dataclass.dataclass import DataEndpointBase, SpeechEndpointBase, EndSpeechEndpointBase, MainEndpointBase, \
    ImageEndpointBase, ResultType
from .network import sessions as sessionFile
from .network.sessions import is_session_valid, sessions
from .agents.MainAgent import MainAgent
from .tools.userInfo_tool import update_user_info
import os

from .utils.audio_processor import get_audio_processor
from .utils.speech_to_text import stt_from_file, tts_to_wav_file

agent = MainAgent()

app = FastAPI()
dirname = os.path.dirname(__file__)

@app.post("/register")
def register_endpoint():
    """
    Register a new user session and return a unique UUID that will be stored by the user's app.
    :return: A JSON response containing the generated UUID and the result type indicating success or failure of the operation.
    """
    generated_uuid_id = sessionFile.generate_uuid()
    if generated_uuid_id != "":
        logger.debug(f"dGenerated new session UUID: {generated_uuid_id}")
        return {"uuid": generated_uuid_id, "type": ResultType.REGISTERED.value}
    else:
        logger.error("Failed to generate a new session UUID")
        return {"type": ResultType.OTHER_ERROR.value, "error": "Failed to generate session UUID"}


@app.post("/data")
def data_endpoint(request: DataEndpointBase):
    """
    Endpoint to receive GPS coordinates and user state information from the user's app.
    This data is stored in a global dictionary for later retrieval by tools.
    :param request: DataEndpointBase containing the session UUID and the user information (GPS coordinates and user state).
    :return: A JSON response indicating the success or failure of the operation, along with any relevant error messages.
    """
    if not is_session_valid(request.uuid): #session isn't existing, refuse access
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
    :param request: SpeechEndpointBase containing the session UUID and the base64 encoded audio chunk.
    :return: A JSON response containing the size of the received chunk, the total accumulated bytes, the estimated duration of the audio, and the result type indicating success or failure of the operation.
    """
    if not is_session_valid(request.uuid):
        logger.warning(f"Invalid session UUID: {request.uuid}")
        return {"type": ResultType.KEY_ERROR.value, "error": "Session not found"}
    try:
        audio_chunk = base64.b64decode(request.audio_data) # Decode base64 audio chunk

        # Add a chunk to the processor, which is stored in memory and accumulates until /audio/finish is called.
        processor = get_audio_processor(request.uuid)
        processor.add_chunk(audio_chunk)

        stats = processor.get_stats()
        # logger.debug(f"Audio chunk received for session {request.uuid}: {len(audio_chunk)} bytes. "
        #             f"Stats: {stats}")

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
    Converts accumulated chunks to WAV and saves to disk. This is the point where we decide to call the LLM.
    In the user app, this is either called when the user stops recording or after a fixed amount of silence.
    :param request: EndSpeechEndpointBase containing the session UUID.
    :return: A JSON response containing the transcription result, the generated response audio in base64, and the result type indicating success or failure of the operation.
    """
    if not is_session_valid(request.uuid):
        logger.warning(f"Invalid session UUID: {request.uuid}")
        return {"type": ResultType.KEY_ERROR.value, "error": "Session not found"}

    try:
        processor = get_audio_processor(request.uuid)
        if not processor.audio_buffer: #No audio chunks received
            logger.warning(f"No audio chunks received for session {request.uuid}")
            return {"type": ResultType.OTHER_ERROR.value, "error": "No audio data recorded"}

        stats = processor.get_stats()

        if stats["total_bytes"] == 0:
            logger.warning(f"No audio data accumulated for session {request.uuid}")
            return {"type": ResultType.OTHER_ERROR.value, "error": "No audio data recorded"}

        wav_data = processor.to_wav_bytes()# Convert to WAV format

        # Create directory if it doesn't exist
        audio_dir = os.path.join(dirname, f"temp/cache/audio/{request.uuid}")

        number_of_files_in_dir = 0

        if not os.path.exists(audio_dir):
            os.makedirs(audio_dir)
        else:
            number_of_files_in_dir = len(os.listdir(audio_dir))

        logger.debug("Saving at audio directory: " + audio_dir)


        #Compute the size of the wav data in bytes and log it
        wav_size = len(wav_data)
        path = os.path.join(audio_dir, f"{number_of_files_in_dir}.wav")

        if wav_size == 0 and number_of_files_in_dir == 0: #No valid audio data after processing => don't call LLM.
            logger.warning(f"WAV data is empty for session {request.uuid} after processing. This may indicate an issue with audio recording or silence stripping.")
            return {"type": ResultType.OTHER_ERROR.value, "error": "No valid audio data after processing"}
        elif wav_size != 0: #Valid audio data, save to disk and proceed with transcription
            with open(path, "wb") as f:
                f.write(wav_data)
            logger.info(f"Audio file saved for session {request.uuid}: {path} ({len(wav_data)} bytes, {stats['duration_seconds']:.2f}s)")
        else:
            path = os.path.join(audio_dir, f"{number_of_files_in_dir-1}.wav")


        processor.clear() # Clear the processor for the next recording

        transcription = stt_from_file(path) # Speech-to-text

        if not transcription["ok"]:
            logger.error(f"STT failed for session {request.uuid}: {transcription}")
            return {"type": ResultType.OTHER_ERROR.value}

        text = transcription["text"]
        logger.info(f"Transcription for session {request.uuid}: {text}")

        session = sessions[request.uuid]

        # First interaction: set the transcription as the goal
        if not session.get("objective"):
            session["objective"] = text
            logger.debug(f"Set objective for session {request.uuid}: {text}")
            result = agent.agent.invoke({
                "messages": [HumanMessage(content=text)],
                "uuid": request.uuid
            })
        else:
            pass
            #Continuation: append the transcription to the existing conversation
            logger.debug(f"Continuing conversation for session {request.uuid} with: {text}")
            existing_messages = session.get("messages", [])
            result = agent.agent.invoke({
                "messages": existing_messages + [HumanMessage(content=text)],
                "uuid": request.uuid
            })

        # Extract the final answer from the agent's messages
        final_message = result["messages"][-1]
        answer = final_message.content if hasattr(final_message, 'content') else str(final_message)

        # Update session messages with the full conversation
        session["messages"] = result["messages"]

        # Generate speech based on answer
        output_path = os.path.join(audio_dir, f"{number_of_files_in_dir}_response.wav")
        tts_result = tts_to_wav_file(text=answer, output_path=output_path)

        if not tts_result["ok"]:
            logger.error(f"TTS failed for session {request.uuid}: {tts_result}")
            return {"answer": answer, "type": ResultType.OTHER_ERROR.value, "error": tts_result.get("error_message", "TTS failed")}

        #Encode to base64
        with open(tts_result["wav_path"], "rb") as f:
            response_audio_data = base64.b64encode(f.read()).decode('utf-8')

        logger.info(f"Generated response audio for session {request.uuid}: {output_path} ({len(response_audio_data)} bytes)")
        return {
            "answer": answer,
            "response_audio_data": response_audio_data,
            "type": ResultType.SUCCESS.value
        }

    except Exception as e:
        logger.error(f"Error finalizing audio: {str(e)}")
        return {"type": ResultType.OTHER_ERROR.value, "error": str(e)}

@app.post("/ask")
def main_endpoint(request: MainEndpointBase):
    """
    Main endpoint to ask the LLM a question.
    :param request: MainEndpointBase containing the session UUID and the user's question.
    :return: A JSON response containing the answer and the result type, depending on the success or failure of the operation.
    """
    if not is_session_valid(request.uuid): #Invalid session
        logger.warning(f"Invalid session UUID: {request.uuid}")
        return {"type": ResultType.KEY_ERROR.value}
    else:
        logger.debug(f"Using existing session UUID: {request.uuid}")

    try:
        # Invoke the agent with the user's question
        logger.debug(f"Invoking agent for session {request.uuid} with question: {request.question}")
        sessions[request.uuid]["objective"] = request.question
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
    """
    Endpoint to receive an image from the user's app. The image is sent as a base64 encoded string and saved to disk for later retrieval by the image_tool.
    :param request: ImageEndpointBase containing the session UUID and the base64 encoded image data.
    :return: A JSON response indicating the success or failure of the operation, along with any relevant error messages.
    """
    if not is_session_valid(request.uuid):
        logger.debug(f"Invalid session UUID: {request.uuid}")
        return {"type": ResultType.KEY_ERROR.value}

    try:
        image_dir = os.path.join(dirname, f"temp/cache/image/{request.uuid}")

        number_of_files_in_dir = 0
        if not os.path.exists(image_dir):
            os.makedirs(image_dir)
        else:
            number_of_files_in_dir = len(os.listdir(image_dir))

        logger.debug(f"Writing image data to {image_dir}")
        decoded_data = base64.b64decode(request.image_data)
        with open(os.path.join(image_dir, f"{number_of_files_in_dir}.jpeg"), "wb") as f:
            f.write(decoded_data)

        return {"type": ResultType.SUCCESS.value}
    except Exception as e:
        logger.error(f"Error processing image: {str(e)}")
        return {"type": ResultType.OTHER_ERROR.value, "error": str(e)}