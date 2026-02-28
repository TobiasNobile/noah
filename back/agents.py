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

history = [
    {
        "role": "system",
        "content": "Analyse l'environnement dans cette image. Indique-moi toutes les directions où je peux aller. Si des panneaux, documents ou autres éléments lisibles sont présents sur l'image, récite-les. Le tout de manière très concise, pour être récité à haute voix."
    }
]

images = sorted(Path(folder).iterdir())
for i, image_path in enumerate(images):
    history.append({
        "role": "user",
        "content": [
            {"type": "image_url", "image_url": {"url": encode_image(image_path)}}
        ]
    })
    response = client.chat.complete(model="pixtral-12b-2409", messages=history)
    answer = response.choices[0].message.content
    print(answer)
    history.append({"role": "assistant", "content": answer})
    if i < len(images) - 1:
        time.sleep(30)