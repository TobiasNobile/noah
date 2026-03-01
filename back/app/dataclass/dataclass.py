from enum import Enum

from pydantic import BaseModel

"""

INFORMATION ABOUT THE USER

"""
class GPS(BaseModel):
    lat: float
    lon: float

class userState(Enum):
    WALKING = "WALKING"
    RUNNING = "RUNNING"
    IDLE = "IDLE"
    TRANSIT = "TRANSIT"
    UNKNOWN = "UNKNOWN"

class UserInfo(BaseModel):
    gps: GPS
    userState: userState

"""

ENDPOINTS

"""
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