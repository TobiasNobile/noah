import os
from mistralai import Mistral
from pathlib import Path
from dotenv import load_dotenv
import base64
import time

load_dotenv()

client = Mistral(api_key=os.environ["MISTRAL_API_KEY"])

folder = "./assets"

def encode_image(image_path):
    image_path = str(image_path)
    ext = os.path.splitext(image_path)[1].lower()
    mime_types = {".jpg": "image/jpeg", ".jpeg": "image/jpeg", ".png": "image/png", ".webp": "image/webp"}
    mime = mime_types.get(ext, "image/jpeg")

    with open(image_path, "rb") as f:
        b64 = base64.b64encode(f.read()).decode("utf-8")
    
    return f"data:{mime};base64,{b64}"

def check_envronnement(image_path):
    response = client.chat.complete(
        model="pixtral-12b-2409",
        messages=[
        {
            "role": "user",
            "content": [
                {"type": "text", "text": 
                 # Prompt here
                 "Analyse l'environnement dans cette image. Indique-moi toutes les directions où je peux aller. Si des panneaux, document ou autres éléments lisibles sont présents sur l'image, récite-les. Le tout de manière assez concise"
                 },
                {"type": "image_url", "image_url": {
                    "url": encode_image(image_path)}
                    }
            ]
        }
    ]
    )

    return response.choices[0].message.content

for image_path in Path(folder).iterdir():
    environnement_description = check_envronnement(image_path)
    print(environnement_description)
    time.sleep(30)