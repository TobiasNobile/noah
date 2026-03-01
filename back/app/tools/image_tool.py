from langchain_core.tools import tool


@tool
def image_tool(uuid: str) -> str:
    """
    Get the description of what the user sees, the research is based on the user's UUID.

    Args:
        uuid (str): The UUID of the user session.
    """
    # TODO: Tobias: implement the actual image processing using a VLM (Vision-Language Model) to generate a description of the image.
    # The image of the user can be retrieved as a jpeg in the folder:
    # /app/temp/cache/image/{session_id}/{index}.jpeg

    #This is a sample desc to just test the integration of tools. You will need to replace it Tobias by the VLM output.
    return ("The image is a photo of a cat sitting on a sofa. "
            "The cat is gray with white paws and has green eyes. "
            "The sofa is blue and has a floral pattern. "
            "The background shows a living room with a window and some plants.")