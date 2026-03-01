import os

import requests
from dotenv import load_dotenv

load_dotenv()

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

                "latitude": 47.683510,
                "longitude": 6.494330
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
else:
    print(f"Request failed with status code {x.status_code}: {x.text}")

