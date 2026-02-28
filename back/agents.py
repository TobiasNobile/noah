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

objectif = "Sortie métro ligne 1, direction La Défense"

history = [
    {
        "role": "system",
        "content":f"""Tu aides une personne malvoyante à naviguer vers : {objectif}.
À chaque frame, tu dois :
1. Décrire brièvement l'environnement
2. Dire si elle avance vers l'objectif ou non
3. Donner la prochaine action concrète (ex: 'Tourne à gauche', 'Continue tout droit', 'Demi-tour')
Si tu n'es pas certain à 100% qu'un panneau ou texte est présent et lisible, ne le mentionne pas. Ne suppose jamais le contenu d'un panneau, récite uniquement ce qui est explicitement visible et lisible.
Sois très concis, le texte sera lu à voix haute."""
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
    response = client.chat.complete(model="pixtral-12b-2409", messages=history, temperature=0.1)
    answer = response.choices[0].message.content
    print(answer)
    history.append({"role": "assistant", "content": answer})
    if i < len(images) - 1:
        time.sleep(30)