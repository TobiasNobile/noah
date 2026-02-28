import os
from mistralai import Mistral
from pathlib import Path
from dotenv import load_dotenv

from back.app.config.config import INITIAL_PROMPT

load_dotenv()

client = Mistral(api_key=os.environ["MISTRAL_API_KEY"])


class NavigationAgent:
    def __init__(self, objectif:str):
        self.history = [
            {
                "role":"system",
                "content": INITIAL_PROMPT.format(objectif=objectif)
            }
        ]

    def ask(self, question, frame:dict):

        response = client.chat.complete(model="pixtral-12b-2409", messages=self.history, temperature=0.1)
        answer = response.choices[0].message.content
        self.history.append({"role": "assistant", "content": answer})
        return answer