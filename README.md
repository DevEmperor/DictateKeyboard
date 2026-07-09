<div align="center">

<img src="img/Icon_512x512_2_round.png" alt="Dictate Keyboard logo" width="120">

# Dictate Keyboard

### Speak instead of type - in any app.

An AI dictation keyboard for Android with real-time transcription, offline speech-to-text,
AI rewriting, Wear OS support and a full FlorisBoard-based typing experience.

<p>
  <a href="https://github.com/DevEmperor/DictateKeyboard/releases"><img alt="Latest release" src="https://img.shields.io/github/v/release/DevEmperor/DictateKeyboard?color=30B7E6&labelColor=1b1e2b&label=release"></a>
  <a href="LICENSE"><img alt="License" src="https://img.shields.io/github/license/DevEmperor/DictateKeyboard?color=30B7E6&labelColor=1b1e2b"></a>
  <img alt="Platform" src="https://img.shields.io/badge/platform-Android%206%2B-30B7E6?labelColor=1b1e2b">
  <a href="https://github.com/DevEmperor/DictateKeyboard/commits/main"><img alt="Last commit" src="https://img.shields.io/github/last-commit/DevEmperor/DictateKeyboard?color=30B7E6&labelColor=1b1e2b"></a>
  <a href="https://github.com/sponsors/DevEmperor"><img alt="Sponsors" src="https://img.shields.io/github/sponsors/DevEmperor?color=30B7E6&labelColor=1b1e2b&label=sponsors"></a>
  <a href="https://github.com/DevEmperor/DictateKeyboard/stargazers"><img alt="GitHub stars" src="https://img.shields.io/github/stars/DevEmperor/DictateKeyboard?style=social"></a>
</p>

<p>
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-7F52FF?logo=kotlin&logoColor=white">
  <img alt="Jetpack Compose" src="https://img.shields.io/badge/Jetpack%20Compose-4285F4?logo=jetpackcompose&logoColor=white">
  <img alt="FlorisBoard" src="https://img.shields.io/badge/Built%20on-FlorisBoard-30B7E6">
</p>

<table align="center">
  <tr>
    <td valign="middle"><a href="https://play.google.com/store/apps/details?id=net.devemperor.dictate"><img alt="Get it on Google Play" width="300" src="https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png"></a></td>
    <td valign="middle"><a href="https://paypal.me/DevEmperor"><img alt="Donate with PayPal" width="200" src="https://www.paypalobjects.com/webstatic/en_US/i/buttons/PP_logo_h_150x38.png"></a></td>
  </tr>
</table>

</div>

---

> [!NOTE]
> Dictate Keyboard is a complete rebuild of Dictate as a standalone keyboard on top of
> [FlorisBoard](https://github.com/florisboard/florisboard). It replaces the original Java app
> from Dictate v1-v3; that codebase is preserved on the
> [`legacy-java`](https://github.com/DevEmperor/Dictate/tree/legacy-java) branch.

## See It In Action

<table>
  <tr>
    <td width="330" align="center">
      <a href="img/dictate_demo.gif"><img src="img/dictate_demo.gif" alt="Dictate demo video" width="300"></a>
    </td>
    <td valign="middle">
      <h3>Tap the mic. Talk naturally. Get clean text.</h3>
      Dictate can type live while you speak, insert the final transcript into the active app,
      rewrite it with AI prompts, or stay fully offline with on-device models. It is not a
      voice note app - it is a keyboard built around speech.
      <br><br>
      <a href="img/dictate_demo.gif"><b>Watch the demo</b></a>
    </td>
  </tr>
</table>

## Screenshots

<table>
  <tr>
    <td><img src="img/banner_01_en-EN.png" alt="Dictate screenshot 1" width="175"></td>
    <td><img src="img/banner_02_en-EN.png" alt="Dictate screenshot 2" width="175"></td>
    <td><img src="img/banner_07_en-EN.png" alt="Dictate screenshot 3" width="175"></td>
    <td><img src="img/banner_04_en-EN.png" alt="Dictate screenshot 4" width="175"></td>
  </tr>
  <tr>
    <td><img src="img/banner_03_en-EN.png" alt="Dictate screenshot 5" width="175"></td>
    <td><img src="img/banner_05_en-EN.png" alt="Dictate screenshot 6" width="175"></td>
    <td><img src="img/banner_06_en-EN.png" alt="Dictate screenshot 7" width="175"></td>
    <td><img src="img/banner_08_en-EN.png" alt="Dictate screenshot 8" width="175"></td>
  </tr>
</table>

## Why Dictate Is Different

| Ordinary voice input | Dictate Keyboard |
| --- | --- |
| Usually tied to one keyboard or one provider | Works as a full keyboard, a classic voice-first panel, a floating button and a Wear OS keyboard |
| Sends audio to a fixed service | Lets you choose cloud providers, OpenAI-compatible endpoints or offline on-device models |
| Gives a raw transcript | Can clean up, translate, summarize, reword, format and auto-fix text before it lands |
| Stops when another keyboard is active | Floating button lets you dictate into apps while keeping your favorite keyboard open |
| Disappears after insertion | Keeps history, stats, resend options and failure recovery for real daily use |

> [!IMPORTANT]
> Dictate does not bundle a hidden speech service. You choose the provider and API key.
> Offline models keep transcription on your device; cloud mode sends audio only to the provider you configure.

## Core Features

### Speech-To-Text That Fits Your Workflow

- **Tap-to-dictate anywhere:** speak into chats, notes, browsers, search fields, forms and productivity apps.
- **Real-time transcription:** watch text appear while you talk with supported streaming providers.
- **Fast batch transcription:** record first, then send the finished audio to the selected provider.
- **Offline transcription:** download on-device Whisper or NVIDIA Parakeet models for private, no-network dictation.
- **File transcription:** transcribe existing audio or video files instead of only recording live speech.
- **Language-aware dictation:** pick a dictation language or let the model auto-detect.
- **Custom words and context:** bias recognition toward names, jargon and recurring terms.
- **Silence detection:** skip empty recordings before they waste an upload or produce hallucinated text.
- **Audio controls:** pause/resume, cancel, retry kept audio, use Bluetooth/voice-communication sources and optional audio focus.
- **Haptic feedback:** optional vibration cues for recording, transcription and rewording state changes.

### AI Rewriting Built Into The Keyboard

- **Prompt chips:** tap saved actions like fix grammar, make formal, summarize, translate or your own custom instruction.
- **Selection rewriting:** select text in any app and rework it in place.
- **Live prompts:** speak an instruction and send it straight to the rewording model.
- **Auto-formatting:** turn spoken punctuation, structure and formatting cues into cleaner text.
- **Auto-apply prompts:** run chosen prompts automatically after each dictation.
- **Per-prompt reasoning effort:** tune reasoning for expensive or complex rewrite prompts without changing the global default.
- **Snippets:** save reusable text snippets that insert instantly without an API call.
- **Community prompt library:** install shared prompts in a tap, or publish your own.

### A Complete Keyboard, Not A Dictation Overlay

- **Full FlorisBoard typing:** glide typing, word suggestions, spell check, autocorrect and rich layout support.
- **Better autocorrect:** keyboard-proximity ranking plus per-language bigram context for more useful corrections.
- **Language data management:** dictionary and context-model status is visible in subtype settings.
- **Classic voice-first layout:** use the old Dictate-style keyboard-free panel, lock it in, or swipe back to the full keyboard.
- **Floating button:** dictate while another keyboard is active, with draggable placement, edge snapping, waveform preview, styles and long-press rewording.
- **Wear OS keyboard:** dictate from your watch, tethered through the phone or standalone with synced settings.
- **Keyboard utilities:** emoji keyboard, clipboard, cursor tools, select-all toggle, one-handed mode, themes, sounds and haptics.

### Control, Reliability And History

- **Provider accounts:** keep separate settings for transcription and rewording providers.
- **Single-call multimodal mode:** send audio to an audio-capable chat model that transcribes and formats in one request.
- **Find and replace rules:** automatically fix names, phrases and common recognition mistakes.
- **Transcription history:** review recent dictations, source, model, language and status.
- **Usage statistics:** track dictated and typed volume with milestone nudges.
- **Resend and recovery:** retry failed or interrupted recordings instead of losing speech.
- **Proxy and certificate options:** support custom network setups, including user-installed certificates.
- **Backup-aware data:** history and settings are handled with app backup/restore behavior in mind.

## Supported Providers

Bring your own API key, choose the provider per use case, or point Dictate at a compatible custom endpoint.

<p align="center"><i>Speech-to-text providers (real-time on supported services)</i></p>
<p align="center">
  <img alt="OpenAI" src="https://img.shields.io/badge/OpenAI-412991?logo=openai&logoColor=white">
  <img alt="Groq" src="https://img.shields.io/badge/Groq-F55036">
  <img alt="Google Gemini" src="https://img.shields.io/badge/Google%20Gemini-4285F4?logo=googlegemini&logoColor=white">
  <img alt="Mistral" src="https://img.shields.io/badge/Mistral-FA520F">
  <img alt="OpenRouter" src="https://img.shields.io/badge/OpenRouter-6467F2">
  <img alt="Soniox" src="https://img.shields.io/badge/Soniox-2A6DF4">
  <img alt="Deepgram" src="https://img.shields.io/badge/Deepgram-13EF93?labelColor=101820">
  <img alt="AssemblyAI" src="https://img.shields.io/badge/AssemblyAI-5D5DFF">
  <img alt="ElevenLabs" src="https://img.shields.io/badge/ElevenLabs-111111">
  <img alt="On-device" src="https://img.shields.io/badge/On--device-Whisper%20%2B%20Parakeet-30B7E6">
</p>

<p align="center"><i>AI rewriting and prompts</i></p>
<p align="center">
  <img alt="OpenAI" src="https://img.shields.io/badge/OpenAI-412991?logo=openai&logoColor=white">
  <img alt="Anthropic Claude" src="https://img.shields.io/badge/Anthropic%20Claude-191919">
  <img alt="Google Gemini" src="https://img.shields.io/badge/Google%20Gemini-4285F4?logo=googlegemini&logoColor=white">
  <img alt="Groq" src="https://img.shields.io/badge/Groq-F55036">
  <img alt="Mistral" src="https://img.shields.io/badge/Mistral-FA520F">
  <img alt="OpenRouter" src="https://img.shields.io/badge/OpenRouter-6467F2">
  <img alt="xAI" src="https://img.shields.io/badge/xAI-111111">
  <img alt="DeepSeek" src="https://img.shields.io/badge/DeepSeek-4D6BFE">
  <img alt="Together AI" src="https://img.shields.io/badge/Together%20AI-111111">
  <img alt="DeepInfra" src="https://img.shields.io/badge/DeepInfra-111111">
  <img alt="Ollama" src="https://img.shields.io/badge/Ollama-111111?logo=ollama&logoColor=white">
  <img alt="Custom OpenAI-compatible endpoint" src="https://img.shields.io/badge/Custom%20endpoint-OpenAI--compatible-30B7E6">
</p>

## Installation

**Dictate is available on [Google Play](https://play.google.com/store/apps/details?id=net.devemperor.dictate)**
for a small fee that supports continued development and includes lifetime updates.

> [!TIP]
> Existing Dictate users can update in place. The new keyboard keeps the same app identity and signing key,
> so settings migrate without reinstalling.

## Built On FlorisBoard

Dictate Keyboard stands on the excellent open-source foundation of
[FlorisBoard](https://github.com/florisboard/florisboard): layouts, theming, gestures, clipboard,
emoji, IME plumbing and many keyboard fundamentals. Dictate adds the voice, provider, prompt,
floating-button, Wear OS and rewording layers on top.

## Support Development

Dictate is open source and built in spare time. The most direct ways to support continued work are:

- [Buy the app on Google Play](https://play.google.com/store/apps/details?id=net.devemperor.dictate)
- [Sponsor DevEmperor on GitHub](https://github.com/sponsors/DevEmperor)
- [Donate via PayPal](https://paypal.me/DevEmperor)

**Dictate's first sponsor - thank you.**

<!-- SPONSORS:START -->
<p>
  <a href="https://github.com/cnfatman"><img src="https://github.com/cnfatman.png" width="72" alt="Codename: Fatman" title="Codename: Fatman - Dictate's first sponsor"></a>
</p>
<!-- SPONSORS:END -->
