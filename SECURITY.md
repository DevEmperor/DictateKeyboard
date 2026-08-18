# Security Policy

Dictate is a keyboard. It sees everything you type, it holds the API keys you give it, and — if
you switch it on — it can read and write text in other apps. That is a lot of trust for one app to
ask for, so a way to report a problem privately matters more here than it does for most.

## Reporting a vulnerability

**Please do not open a public issue for anything that could be used against someone's device or
data.** Use GitHub's private reporting instead:

> **[→ Report a vulnerability privately](https://github.com/DevEmperor/DictateKeyboard/security/advisories/new)**

Only you and I can see the report, and it turns into a published advisory once a fix is out —
with credit, unless you would rather not be named.

Useful in a report: what you did, what happened, the Android version and device, and the Dictate
version from *Settings → About*. A proof of concept is welcome but never required; a clear
description of the mechanism is worth more than a working exploit.

### What to expect

Dictate is maintained by one person. You will get an acknowledgement as soon as I see the
report — usually within a few days. I will tell you what I found and, if it is a real issue, when
a fix ships. If I go quiet for longer than that, ping the thread; you will not be ignored on
purpose.

Fixes go into the next release. Only the latest release is supported — there is no backporting to
older versions.

## In scope

The Android app, the Wear OS app, and the Dictate Cloud backend. In particular:

- Anything that exposes a stored provider API key, or a Dictate Cloud wallet or recovery code, to
  another app or to someone on the network.
- Anything that lets another app read what is typed or dictated, or drive the floating button's
  accessibility service.
- Anything that sends audio or text somewhere other than the provider you configured.
- Anything server-side in Dictate Cloud: manipulating credit, reaching another person's balance or
  history, or getting transcription without paying for it.
- Extensions and language packs: a crafted `.flex` that escapes its directory, overwrites files
  outside it, or gets code executed.
- Anything that weakens or bypasses TLS beyond the switches documented below.

## Not vulnerabilities

These are how the app is built, and reporting them will get you this section quoted back:

- **Your audio and text go to the AI provider you configured.** That is what the app is for; the
  [privacy policy](PRIVACY_POLICY.md) describes exactly what leaves the device and when. Choosing
  the on-device engine is how you avoid it entirely.
- **Your API key is stored on the device**, in Dictate's private storage. An attacker who already
  has root, an unlocked bootloader, or physical access to an unlocked phone can read it — that is
  the Android storage model, not a flaw specific to Dictate.
- **Plain HTTP is permitted**, so that a speech server on your own network can be reached at all.
  It applies only to a base URL you typed in yourself.
- **"Trust user certificates" exists**, off by default, for self-hosters running their own CA.
  Turning it on is a deliberate choice with consequences you are choosing to accept.
- **The accessibility service is opt-in**, used only by the floating button, and can be revoked at
  any time in Android's settings.
- Reports produced by an automated scanner with no explanation of how the finding is reachable in
  Dictate. Send the reasoning, not the tool output.

## Third-party components

Dictate is a fork of [FlorisBoard](https://github.com/florisboard/florisboard) and ships several
upstream libraries and on-device models. A vulnerability in one of those is best reported to that
project — but tell me too, so the dependency can be moved here.
