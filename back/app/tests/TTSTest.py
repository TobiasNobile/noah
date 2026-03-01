from back.app.utils.speech_to_text import tts_to_wav_file, stt_from_file
def tts():
    tts_to_wav_file(
        text="Hello, this is a test of the TTS to WAV file function.",
        output_path="tts-sst-files/test_output.wav")

def sst():
    result = stt_from_file("tts-sst-files/audio.wav")
    print(result)

sst()