from enum import Enum

from langchain_core.tools import Tool
from pydantic import BaseModel

class GPS(BaseModel):
    lat: float
    lon: float
    altitude: float
    accuracy: float

class userState(Enum):
    WALKING = "WALKING"
    RUNNING = "RUNNING"
    IDLE = "IDLE"
    TRANSIT = "TRANSIT"

class UserInfo:
    gps: GPS
    userState: userState

@Tool
def userInfo_tool() -> UserInfo:
    """Get the current information about the user to get its coordinates, location and state.
    The state purpose is to know if the user is walking, running, idle or on transit.
    """
    pass

@Tool
def userInfo_gpsToCity_tool() -> str:
    """Get the city name from the GPS coordinates."""
    pass