![](Noah_wallpaper.png?raw=true)


## A brief description

**Noah** is an Android application designed to empower people with visual impairments or literacy challenges. Using the phone's camera, microphone, and GPS, Noah acts as an intelligent companion that perceives the world and speaks back — so that no one is left behind by a world built for sighted, literate people.

Noah is built around one core belief: **technology should adapt to people, not the other way around.** 

## For which uses ?

### Navigation for the visually impaired
Blind or visually impaired users can activate Noah and explore their surroundings with confidence. Noah uses real-time camera input and GPS location to describe the environment, identify obstacles, and guide users through complex spaces such as metro stations, shopping centers, or unfamiliar streets — all through natural voice interaction.

###  Reading assistance for illiterate users
For people who cannot read — whether due to illiteracy, functional illiteracy, or a language barrier — Noah can read any document, sign, or text aloud. Simply point the camera at a page, a label, or a screen, and Noah will read and explain the content in plain spoken language.

##  How it works

When the user activates tracking, Noah:

1. **Captures frames** from the camera and sends them to the Mistral AI backend
2. **Records ambient audio** and streams it for voice interaction
3. **Tracks GPS position** to provide location-aware context
4. **Speaks responses** back to the user via Text-to-Speech

Everything is designed to work **eyes closed, hands free.**

---

## Accessibility first

Every design decision prioritizes accessibility:

- **Giant single button** to start and stop — no complex navigation
- Full **TalkBack** compatibility (no images used as buttons)
- **Haptic feedback** on every interaction
- **Voice confirmation** of every action via TTS
- Configurable server settings for offline / local deployments

---
## Features
- Deployable localhost fastAPI server that can used by the Noah app (change the settings in the app).
- Speech to text to transform user vocal request to a text request, understable for a LLM.
- Agent that has the capability to choose the tools he needs.
- The LLM can retreive some popular places around you like restaurant names.
- The LLM can retreive your city and street.
- The VLM can analyse your camera to fully help you.
- The LLM's answer is transcribed to a text to speech in order for easier access without Talkback.
---

##  Installation & Setup

### Prerequisites

App:
- The APK.

Server:
- FastAPI server with pre-installed librairies (requirements.txt)


## 👥 Contributors

| Name | Role                                  |
|---|---------------------------------------|
| **GoldRen** | Android development  + Vision model   |
| **Theo** | Android development + Backend architecture & Mistral AI API |
| **Ayush** | Backend architecture & Mistral AI API |
| **Harsh** | Backend architecture & Mistral AI API |

