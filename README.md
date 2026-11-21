# J.A.R.V.I.S for Android

A pure native Android (Kotlin + Jetpack Compose + Material 3) HUD assistant rewritten from the `ggcreation07/jarvis` workspace, preserving the dark `#04070D` / `#00E5FF` Modern Orb and Iron Man Arc Reactor visual modes.

## Features
- **Home HUD & Customizable Looks**: Live clock/greeting, animated **Modern Orb** and **Iron Man Arc Reactor** centerpieces, live waveform, quick Ask bar, starter chips, and collapsible/reorderable widget cards (*Status*, *Routines*, *Device*, *Battery*, *Agenda*, *Weather*, *Network*, *Action log*).
- **Built-in Gemini AI + Optional BYO Keys**: Powered by **Firebase AI Logic** (`gemini-3.8-flash` default and `gemini-nano-banana-2.1` runtime image generation) secured with App Check, plus Android Keystore AES-256-GCM encrypted support for optional BYO keys (Groq, Google AI Studio, OpenRouter, NVIDIA NIM, Mistral, Tavily).
- **Conversation & Agents (`@agent` Routing)**: Streaming multi-tool orchestration, multimodal photo attachment via Android Photo Picker, structured `[CHART]`, `[TABLE]`, `[FLOWCHART]`, and `[SCHEDULE]` visual blocks, QR code generation, and searchable Recent Chats.
- **Persistent Memory & Routines**: Room-backed facts ("About you"), Playbooks, archived threads, and AlarmManager-scheduled routines with exact/inexact alarms and timezone/boot recovery.
- **Voice, Hands-Free & MediaPipe Gestures**: On-device `SpeechRecognizer` + `TextToSpeech`, Paged Reader, Follow Steps checklist with countdown timer, media controls, and CameraX + MediaPipe `GestureRecognizer` hand-pose detection.
- **Android Hardware & Remote Pairing**: Flashlight, media volume, DND, Clock alarms/timers, Maps navigation, SAF granted folder access, Android Calendar agenda sync, Notification reader, optional Accessibility helper, Wake-on-LAN, and Remote PC/TV controls.

## Tech Stack
- **Language / UI**: Kotlin, Jetpack Compose (Material 3), Coroutines & Flow
- **AI**: Firebase AI Logic (`firebase-ai`), Firebase App Check (`recaptcha` + `debug`)
- **Storage**: Room (`JarvisDatabase`), Jetpack DataStore (`ConfigStore`), Android Keystore (`SecureSecretStore`)
- **Networking & Vision**: Ktor Client (OkHttp + WebSockets), Coil 3, ZXing, CameraX, MediaPipe Tasks Vision
- **Tests**: Robolectric (`gradle :app:testDebugUnitTest`)
