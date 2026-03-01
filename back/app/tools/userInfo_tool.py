import logging
from enum import Enum
from typing import Dict

from langchain_core.tools import tool
from pydantic import BaseModel

logger = logging.getLogger(__name__)
logger.setLevel(logging.DEBUG)


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

userInfo: Dict[str, UserInfo] = {}

def update_user_info(uuid: str, user_info: UserInfo):
    """Update the user information in the global dictionary."""
    logger.debug("Updated to info: %s", user_info)
    userInfo[uuid] = user_info

def retrieve_user_info(uuid: str) -> UserInfo:
    """Retrieve the user information from the global dictionary."""
    logger.debug("Retrieved info: %s", userInfo.get(uuid, None))
    return userInfo.get(uuid, None)

@tool
def userInfo_tool(uuid: str) -> UserInfo:
    """Get the current information about the user to get its coordinates, location and state.
    The state purpose is to know if the user is walking, running, idle or on transit. The research is based on the user's UUID.

    Args:
        uuid (str): The UUID of the user session.
    """
    return retrieve_user_info(uuid)
    # falseGps = GPS(lat=0.0, lon=0.0, altitude=0.0, accuracy=10000.0)
    # return UserInfo(gps=falseGps, userState=userState.WALKING)

@tool
def userInfo_gpsToCity_tool(uuid: str) -> str:
    """Get the city name from the GPS coordinates. The research is based on the user's UUID.

    Args:
        uuid (str): The UUID of the user session."""
    # TODO: use a geocoding API to convert the GPS coordinates to a city name.
    return "Paris, La Défense"

