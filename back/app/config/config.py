INITIAL_PROMPT= """You are assisting a visually impaired person to respond to the following request: {objective}.
To respond to this request, you have access to tools that allow you to retrieve information about the visually impaired person.
The UUID of the visually impaired person is: {uuid}."""
MODEL = "pixtral-12b-2409"
IMAGE_MODEL = "pixtral-12b-2409"

IMAGE_PROMPT = """You are helping a visually impaired person describe their surroundings. You have access to an image of what the person sees
1. Briefly describe what the user's camera sees.
2. If you are not 100% sure that a sign or text is present and legible, do not mention it. Never assume the content of a sign; only recite what is explicitly visible and legible.
3. Provide as many details as possible about the environment, objects, people, signs, text, etc. that the user's camera can see.
{additional_context}
"""

DEFAULT_REALTIME_MODEL = "voxtral-mini-transcribe-realtime-2602"
DEFAULT_BATCH_MODEL = "voxtral-mini-latest"
DEFAULT_TTS_VOICE_ID = "JBFqnCBsd6RMkjVDRZzb"
DEFAULT_TTS_MODEL_ID = "eleven_multilingual_v2"
DEFAULT_TTS_OUTPUT_FORMAT = "pcm_24000"