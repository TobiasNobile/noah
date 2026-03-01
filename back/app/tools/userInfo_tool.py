from enum import Enum

from langchain_core.tools import tool
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

@tool
def userInfo_tool(uuid: str) -> UserInfo:
    """Get the current information about the user to get its coordinates, location and state.
    The state purpose is to know if the user is walking, running, idle or on transit. The research is based on the user's UUID.

    Args:
        uuid (str): The UUID of the user session.
    """
    falseGps = GPS(lat=0.0, lon=0.0, altitude=0.0, accuracy=10000.0)
    return UserInfo(gps=falseGps, userState=userState.WALKING)

@tool
def userInfo_gpsToCity_tool(uuid: str) -> str:
    """Get the city name from the GPS coordinates. The research is based on the user's UUID.

    Args:
        uuid (str): The UUID of the user session."""
    return "Paris, La Défense"

