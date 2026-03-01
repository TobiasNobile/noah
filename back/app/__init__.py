import logging
import os
import shutil
"""
Keeping cache clean at every startup to prevent high storage usage.
"""
def clean_cache():
    audio_cache = 'temp/cache/audio/'
    image_cache = 'temp/cache/image/'
    for folder in [audio_cache, image_cache]:
        if os.path.exists(folder):
            shutil.rmtree(folder)
        os.makedirs(folder)

clean_cache()

logging.basicConfig(
    level=logging.DEBUG,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)
logger.setLevel(logging.DEBUG)