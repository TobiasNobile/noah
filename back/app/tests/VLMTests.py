import os
import shutil

from back.app.network.sessions import sessions
from back.app.routes import main_endpoint, MainEndpointBase

question = "What's in front of me?"
exampleUUID = "testUUIDVLM"

mainEndpoint = MainEndpointBase(uuid = exampleUUID, question = question)

dirname = os.path.dirname(__file__)

image_dir = os.path.join(dirname, f"../temp/cache/image/{exampleUUID}")
imageToCopy = os.path.join(dirname, f"../../../assets/images/ocr/3.JPG")

print("From: ", imageToCopy)
print("To: ", image_dir)

if not os.path.exists(image_dir):
    os.makedirs(image_dir)

shutil.copyfile(imageToCopy, os.path.join(image_dir, "0.jpeg"))

sessions[exampleUUID] = {"messages": [], "llm_calls": 0, "uuid": exampleUUID, "objective": question}
answer = main_endpoint(mainEndpoint)