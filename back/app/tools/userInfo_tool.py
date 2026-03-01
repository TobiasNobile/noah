import logging
import os
from enum import Enum
from typing import Dict

import requests
from dotenv import load_dotenv
from langchain_core.tools import tool
from pydantic import BaseModel

from ..dataclass.dataclass import UserInfo

logger = logging.getLogger(__name__)
logger.setLevel(logging.DEBUG)


userInfo: Dict[str, UserInfo] = {}
load_dotenv()

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

@tool
def userInfo_gpsToCity_tool(uuid: str) -> str:
    """Get the city name from the GPS coordinates. The research is based on the user's UUID.

    Args:
        uuid (str): The UUID of the user session."""

    retrievedUserInfo = retrieve_user_info(uuid)
    if retrievedUserInfo is None:
        logger.error(f"No user info found for UUID: {uuid}")
        return "No user info found for this UUID."
    elif retrievedUserInfo.gps.lat == 0.0 and retrievedUserInfo.gps.lon == 0.0:
        return "The user is in an unknown location."
    else:
        header = {
            "X-Goog-FieldMask": "places.formattedAddress",
            "Content-Type": "application/json",
            "X-Goog-Api-Key": os.getenv("GOOGLE_API_KEY")
        }

        payload = {
            "locationRestriction": {
                "circle": {
                    "center": {
                        "latitude": retrievedUserInfo.gps.lat,
                        "longitude": retrievedUserInfo.gps.lon
                    },
                    "radius": 20
                }
            }
        }

        response = requests.post(
            "https://places.googleapis.com/v1/places:searchNearby",
            json=payload,
            headers=header
        )

        if response.status_code == 200:
            print("Request successful!")
            print(response.json())
            jsonResult = response.json()

            if "places" in jsonResult and jsonResult["places"]:
                if "formattedAddress" in jsonResult["places"][0]:
                    print("Formatted Address:", jsonResult["places"][0]["formattedAddress"])
                else:
                    print("Address not found in the results.")
            else:
                print("No places found for the provided GPS coordinates.")
        else:
            print(f"Failed to retrieve city information. Status: {response.status_code}")
            print(response.json())


@tool
def userInfo_gpsGetPlacesAround(uuid: str) -> str:
    """Get the places around the user from the GPS coordinates. The research is based on the user's UUID.

    Args:
        uuid (str): The UUID of the user session."""

    retrievedUserInfo = retrieve_user_info(uuid)
    if(retrievedUserInfo is None):
        logger.error(f"No user info found for UUID: {uuid}")
        return "No user info found for this UUID."
    elif retrievedUserInfo.gps.lat == 0.0 and retrievedUserInfo.gps.lon == 0.0:
        return "The user is in an unknown location."
    else:
        jsonObject = {
            "includedPrimaryTypes": [
                "restaurant",
                "cafe",
                "bar"
            ],
            "maxResultCount": 10,
            "locationRestriction": {
                "circle": {
                    "center": {

                        "latitude": retrievedUserInfo.gps.lat,
                        "longitude": retrievedUserInfo.gps.lon
                    },
                    "radius": 20
                }
            }
        }

        header = {
            "X-Goog-FieldMask": "places.displayName",
            "Content-Type": "application/json",
            "X-Goog-Api-Key": os.getenv("GOOGLE_API_KEY")
        }
        x = requests.post("https://places.googleapis.com/v1/places:searchNearby", json=jsonObject, headers=header)
        if x.status_code == 200:
            print("Request successful!")
            print(x.json())
            jsonResult = x.json()
            proximitedPlaces = "Places around the user:\n"
            if jsonResult["places"]:
                for place in jsonResult["places"]:
                    proximitedPlaces += f"- {place['displayName']['text']}\n"

            return proximitedPlaces
        else:
            return "No places found around the user or the tool is not working properly."