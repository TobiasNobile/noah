import base64
import os

from langchain_core.tools import tool


@tool
def image_tool() -> str:
    """
    Get the description of what the user sees.
    """
    return ("The image is a photo of a cat sitting on a sofa. "
            "The cat is gray with white paws and has green eyes. "
            "The sofa is blue and has a floral pattern. "
            "The background shows a living room with a window and some plants.")


def encode_image(image_path):
    image_path = str(image_path)
    ext = os.path.splitext(image_path)[1].lower()
    mime_types = {".jpg": "image/jpeg", ".jpeg": "image/jpeg", ".png": "image/png", ".webp": "image/webp"}
    mime = mime_types.get(ext, "image/jpeg")

    with open(image_path, "rb") as f:
        b64 = base64.b64encode(f.read()).decode("utf-8")

    return f"data:{mime};base64,{b64}"