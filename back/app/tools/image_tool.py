import base64
import os
from dotenv import load_dotenv

from langchain_core.messages import SystemMessage, HumanMessage
from langchain_core.tools import tool
from langchain_mistralai import ChatMistralAI

from back.app.config.config import IMAGE_MODEL, IMAGE_PROMPT

load_dotenv()

dirname = os.path.dirname(__file__)
# Ensure MISTRAL_API_KEY is set in environment
mistral_api_key = os.getenv("MISTRAL_API_KEY")

if not mistral_api_key:
    raise ValueError("MISTRAL_API_KEY environment variable is not set. Please set it in your .env file or as an environment variable before running the application.")
vlm = ChatMistralAI(model=IMAGE_MODEL, temperature=0.1)

def encode_image(image_path):
    """
    Encode image as base64 for Mistral API.
    Returns the base64 string without the data URL prefix for proper Mistral handling.
    """
    image_path = str(image_path)

    with open(image_path, "rb") as f:
        b64 = base64.b64encode(f.read()).decode("utf-8")

    # Return just the base64 string - Mistral will handle it properly
    return b64

@tool
def image_tool(uuid: str, additionalContext: str) -> str:
    """
    Get the description of what the user sees, the research is based on the user's UUID.

    Args:
        uuid (str): The UUID of the user session.
        additionalContext (str): Additional context that can be used to enhance the image description.
    """
    image_dir = os.path.join(dirname, f"../temp/cache/image/{uuid}")
    totalImages = len(os.listdir(image_dir))

    if totalImages == 0:
        return "No images found for this user session."
    else:
        selectedImage = image_dir + "/" + str(totalImages - 1) + ".jpeg"

        print("Found image path: ", selectedImage)

        # Get file extension to determine image media type
        ext = os.path.splitext(selectedImage)[1].lower()
        media_type_map = {
            ".jpg": "image/jpeg",
            ".jpeg": "image/jpeg",
            ".png": "image/png",
            ".webp": "image/webp",
            ".gif": "image/gif"
        }
        media_type = media_type_map.get(ext, "image/jpeg")

        # Encode image as base64
        encodedImage = encode_image(selectedImage)

        system_message = SystemMessage(
            content=IMAGE_PROMPT.format(additional_context=additionalContext)
        )

        # Create the message with proper image content format for Mistral
        human_message = HumanMessage(
            content=[
                {
                    "type": "image_url",
                    "image_url": {
                        "url": f"data:{media_type};base64,{encodedImage}"
                    }
                }
            ]
        )

        messages = [system_message, human_message]

        return str(vlm.invoke(messages))
