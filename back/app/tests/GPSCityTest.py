import os

import requests
from dotenv import load_dotenv

load_dotenv()

# Using Google Places API with field masking to only request formatted_address
header = {
    "X-Goog-FieldMask": "places.formattedAddress",
    "Content-Type": "application/json",
    "X-Goog-Api-Key": os.getenv("GOOGLE_API_KEY")
}

payload = {
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
