# Privacy Policy for Dictate Keyboard

**Effective date:** 31 August 2026
**Last updated:** 31 August 2026

This Privacy Policy explains how **Dictate Keyboard** (the "App", application ID
`net.devemperor.dictate`) handles your information. The App is developed and
maintained by **Jannis Zahn**, trading as DevEmperor (the "Developer", "we",
"us"), Talstraße 84, 35625 Hüttenberg, Germany — the data controller for the
limited purposes described below.

If you have any questions about this policy, contact us at
**contact@devemperor.net**.

---

## 0. Two ways to use this App, and only one of them involves us

Dictate Keyboard turns your speech into text. There are two ways to set that up,
and they differ completely in what happens to your data:

**By default, we are not involved at all.** You bring your own API key for a
provider you choose, or you use on-device recognition. Your audio goes from your
phone straight to that provider, or never leaves the phone. We operate no server
in that path, receive nothing, and store nothing about you.

**Dictate Cloud is optional.** If — and only if — you deliberately buy credit
through Google Play, your recordings pass through a server we operate. That is
the only situation in which any of your data reaches us, and it is described
separately in section 4.

**Nothing about the first way changed when the second was added.** If you never
buy credit, this policy applies to you exactly as it did before Dictate Cloud
existed: we have no server in your path and no data about you.

---

## 1. Summary (the short version)

- **Unless you choose Dictate Cloud, we do not receive any of your data.** No
  backend in your path, no database about you, no analytics.
- Dictate Keyboard is a voice-to-text keyboard. To turn your speech into text it
  sends the audio you record to an **AI provider that you choose and configure**
  (for example OpenAI, Groq, or a self-hosted server). That transfer happens
  **directly from your device to that provider** — it never passes through us.
- **On-device recognition is available** and needs no network connection and no
  provider at all.
- **Dictate Cloud is the one exception**, it is entirely optional, it is never
  switched on without a purchase you make yourself, and section 4 says exactly
  what it does.
- Your API keys, prompts, and settings are stored **only on your device**.
- The App contains **no advertising, no tracking, no telemetry, and no
  crash-reporting SDKs**.
- As a keyboard, the App **does not log your keystrokes or collect what you type**
  in other apps. It only processes audio that you explicitly record by pressing
  the dictation button.
- **All of this can be checked.** The App and the Dictate Cloud server are open
  source and readable in full — see section 8.

---

## 2. What information the App processes

### 2.1 Voice recordings
When you start a dictation, the App records audio using your device microphone.
The recording is written to a temporary, app-private cache file on your device
and is **deleted automatically** once the transcription completes. To produce a
transcript, this audio is uploaded to the transcription provider you have
selected — or processed entirely on your device if you use on-device
recognition.

If you enable the "resend / retry" convenience feature, the most recent recording
may be kept temporarily so it can be sent again; it is deleted when you start a
new dictation, when you clear it, or when the App removes it.

### 2.2 Text you transcribe or reword
The transcribed text — and, if you use the rewording / AI-formatting feature, the
text you ask the App to rephrase — is sent to the AI provider you have configured
so the requested result can be returned. The provider returns the text to your
device, where it is inserted into the field you are typing in.

### 2.3 Dictation history
The App keeps a history of your dictations **on your device** so you can
re-insert, review or re-transcribe them. It is **on by default** and holds the
most recent entries (50 unless you change it), which you can search, pin and
delete individually or all at once in the App's settings.

- **Transcripts** are stored in an app-private database on your device.
- **The source audio is not kept** unless you switch that on separately; it is
  **off by default**. Failed transcriptions are an exception: their audio is kept
  so the attempt can be repeated, and is removed with the entry.
- **Nothing is recorded in password fields or in incognito mode.**
- None of this is uploaded anywhere. It exists only on your device and is deleted
  when you clear the history or uninstall the App.

### 2.4 API keys and provider configuration
To use a provider, you enter your own API key (or, for a local/self-hosted server
such as Ollama, a base URL). These credentials and provider settings are stored
**locally on your device** in the App's private storage. They are sent **only** to
the corresponding provider's API to authenticate your own requests, and are
**never** transmitted to the Developer.

### 2.5 Prompts and settings
Custom rewording prompts, style settings, language preferences, and other
configuration are stored **locally on your device** (in an app-private settings
store and a local SQLite database). They are not uploaded anywhere except as part
of a request to your chosen provider when relevant (e.g. a prompt you send for
rewording).

---

## 3. Third-party services you choose

### 3.1 AI providers
To perform transcription and rewording, the App acts as a client to an
OpenAI-compatible API **that you select and authenticate with your own account**.
When you use such a feature, your audio and/or text is processed by that provider
under **their** privacy policy and terms — they act as independent data
controllers, not on our behalf. We have no access to, and no control over, the
data you send them or what they do with it.

Built-in providers the App can be configured to use include:

| Provider | Privacy policy |
| --- | --- |
| OpenAI | https://openai.com/policies/privacy-policy |
| Groq | https://groq.com/privacy-policy/ |
| OpenRouter | https://openrouter.ai/privacy |
| Together AI | https://www.together.ai/privacy |
| DeepInfra | https://deepinfra.com/privacy |
| Mistral AI | https://mistral.ai/terms/#privacy-policy |
| xAI (Grok) | https://x.ai/legal/privacy-policy |
| DeepSeek | https://cdn.deepseek.com/policies/en-US/deepseek-privacy-policy.html |
| Anthropic | https://www.anthropic.com/legal/privacy |
| Deepgram | https://deepgram.com/privacy |
| AssemblyAI | https://www.assemblyai.com/legal/privacy-policy |
| ElevenLabs | https://elevenlabs.io/privacy |
| Soniox | https://soniox.com/privacy |
| Ollama (local) | Runs on your own device/server — no third party involved |
| On-device recognition | Runs entirely on your phone — no third party involved |

You may also configure a **custom OpenAI-compatible endpoint**. If you do, your
data is sent to whichever server you specify, and you are responsible for that
server's data handling.

**Please review the privacy policy of any provider before you use it.** Because
the data is sent with your own API key, the provider may retain or process it
according to your account terms with them (for example, to deliver the service or
for abuse monitoring).

### 3.2 GIF search
If you use the GIF panel, the search terms you type there are sent to **KLIPY**
(https://klipy.com/privacy-policy) to fetch results, using an API key you supply
yourself. The feature is unavailable until you configure that key, and nothing is
sent to KLIPY unless you open the panel and search.

### 3.3 Google Play
Purchases of Dictate Cloud credit are processed by **Google**, who is the seller
and an independent controller for the payment. We never see your payment details.
See https://policies.google.com/privacy.

---

## 4. Dictate Cloud (optional credit)

**This section applies only if you buy credit.** If you use your own API key or
on-device recognition, none of it applies to you and nothing here changes what is
described above.

Dictate Cloud exists for people who do not want to create an account with an AI
provider. You buy dictation minutes through Google Play, and your recordings are
sent to a server we operate, which has them transcribed on **Cloudflare Workers
AI** and returns the text. Rewording works the same way.

### 4.1 What we process

**Recordings and text.** Your recording is sent to our server, **not stored
there**, and passed straight on to Cloudflare Workers AI for transcription. The
same applies to text you have reworded. We keep none of it — no audio, no
transcript, no prompt.

**Nothing is retained at the other end either.** Workers AI runs the request and
keeps nothing afterwards: there is no copy to delete, because none is made. Your
recordings and text are not used to train any model.

**Your credit account.** We store a random identifier, your balance, a hash of
your recovery code and hashes of your access keys. There is no account in the
usual sense: we hold no name, no email address and no Google account.

**Device name.** So you can see in the settings which devices use your credit, a
label such as "SM-A556B · Android 15 · 5.4.0" is stored.

**Purchases.** For each purchase we store the order number, the pack, the amounts
Google reports, the currency and the billing country. These records are subject
to statutory retention (see 4.4).

**Usage log.** For each request we store the time, the kind (dictation or
rewording), the seconds billed, token counts, cost, a status code and the
duration — **numbers only, never content.** These individual rows are deleted
after **90 days**; what remains are daily totals with no link to any account.

**A hash of your previous credit account.** When you buy while a credit account
already exists on your device, the App attaches that account's random identifier
to the purchase and Google returns it to us with the order. We store only a
**SHA-256 hash** of it. It is not a Google identifier and says nothing about your
Play account, your name or your email address; it exists solely to recognise
repeated refunds after full consumption. A first purchase, or one made after you
have deleted your credit account, carries nothing at all.

### 4.2 Legal bases

| Purpose | Basis |
| --- | --- |
| Providing the service, credit, handling purchases | Art. 6(1)(b) GDPR (contract) |
| Detecting abuse, operational security, alerting | Art. 6(1)(f) GDPR (legitimate interests) |
| Retaining purchase records | Art. 6(1)(c) GDPR with § 147 AO |

### 4.3 Recipients and where the data is

- **Cloudflare, Inc.**, USA — **one processor in two roles**: it operates our
  server and database, *and* it transcribes and rewords your text on Workers AI.
  Both rest on Cloudflare's data processing addendum, which forms part of its
  terms, and that transfer relies on the European Commission's **standard
  contractual clauses**.
  - For Workers AI, Cloudflare names two sub-processors of its own:
    **CoreWeave, Inc.** (United States) and **Nebius BV** (England). The
    inference does not run on Cloudflare's own hardware. Transfers to CoreWeave
    rest on the same standard contractual clauses; England is covered by an
    adequacy decision under Art. 45 GDPR.

**Where your data actually is — and the part we cannot promise.** Our database
and your balance are held in the **European Union** (Western Europe); we pinned
them there deliberately.

**That pinning does not extend to transcription and rewording.** On our plan the
location of the inference cannot be chosen — Cloudflare offers that only to
enterprise customers — so a request runs wherever there is capacity, which may be
the United States or England. We would rather say so plainly than let the
sentence about the database be read as covering everything.

Pinning would not be the whole answer in any case: Cloudflare is a US company,
and access from the United States is itself a transfer under data protection law
even when the bytes sit in Europe. What carries this is the standard contractual
clauses above — and the fact that **nothing is kept**, so there is no store for
anyone to reach into.

### 4.4 How long we keep it

| What | How long |
| --- | --- |
| Recordings, transcripts, prompts | not at all — neither with us nor at Cloudflare |
| Usage log (individual rows) | 90 days |
| Daily totals | indefinitely, with no link to an account |
| Credit account, access keys | until you delete it |
| Purchase records | 10 years (§ 147 AO) |

### 4.5 Deleting your credit account

You can delete it **in the App** under Settings → Dictate Cloud, or **in a
browser** at **https://api.dictatekeyboard.com/delete** if you no longer have the
App installed. Deleting removes your account, your recovery code, every signed-in
device and your usage log.

**Any remaining credit is forfeited. It is not refunded.** If you want your money
back, contact Google Play before deleting.

Your purchase records remain for the retention period above; they hold an order
number and an amount, and once the account is gone there is nothing in them that
leads back to you. Because those records refer to the account, the account's own
entry is emptied rather than dropped: what is left is the random identifier, the
dates it was created and deleted, and the minute totals. No recovery code, no
device, nothing that can be signed in to.

**One exception, and we would rather name it than bury it.** The one-way hash
described in section 4.1 is kept for **24 months** after deletion, and then
erased. It exists solely to recognise repeated refunds after full consumption:
without it, deleting the account would reset that count and make deletion the
last step of a refund cycle rather than an end to the relationship. It is a
random identifier of ours, and cannot be turned back into an account, an email
address or a name. The basis is Art. 6(1)(f) GDPR, our legitimate interest in
preventing payment abuse.

### 4.6 Identifying you

Because we deliberately store no name, no email address and no account, **we
cannot identify you.** If you ask us for access to or erasure of your data, we
therefore need your **recovery code** or the account identifier shown in the App.
Without one of those we cannot connect a request to any data, and Art. 11 GDPR
does not require us to collect more information just to be able to (Art. 12(2)).

---

## 5. Permissions and why they are used

| Permission | Purpose |
| --- | --- |
| `RECORD_AUDIO` | To capture your voice when you press the dictation button. |
| `INTERNET` | To send audio/text to the AI provider you configured and receive the result. |
| `MODIFY_AUDIO_SETTINGS` / `BLUETOOTH` | To route recording correctly, including through Bluetooth headsets. |
| `VIBRATE` | Optional haptic feedback. |
| `POST_NOTIFICATIONS` | To show status notifications (e.g. transcription progress) on Android 13+. |

The App requests the microphone permission only for dictation and uses it only
while you are actively recording. **The App does not request access to your
contacts.**

You can teach the App a contact's name so it stops autocorrecting it, and that
works without giving it your address book: you either pick a single contact in
the system's own contact picker — which hands over that one contact and nothing
else — or select a contacts file you exported yourself. Only the name is taken
from it, never numbers, addresses or anything else, and it is stored in your
personal dictionary on the device, exactly like any other word you add there.
You can see and delete these names in the App's dictionary settings.

---

## 6. Data storage and retention

- **On your device:** API keys, settings, prompts and your dictation history
  remain on your device until you delete them in the App or uninstall it.
  Temporary audio recordings are deleted automatically after transcription.
- **With your chosen provider:** any retention of audio or text is governed by
  that provider's policy, not by us.
- **With the Developer:** nothing — unless you use Dictate Cloud, where section
  4.4 applies.

To erase all locally stored data, clear the App's keys, history and settings in
its settings screens, or uninstall the App.

---

## 7. We do not collect what you type

Although Dictate Keyboard is an input method (a keyboard), it does **not** record,
log, store, or transmit your general typing. Only audio you deliberately record
for dictation is processed, and only for the purpose of returning a transcript.
Password fields and incognito mode are excluded from the dictation history
entirely.

---

## 8. You do not have to take our word for any of this

The App is open source, and so is the server behind Dictate Cloud. Both live in
the same public repository:

**<https://github.com/DevEmperor/DictateKeyboard>** — the App under `app/` and
`lib/`, the Dictate Cloud server under `cloud/`.

You can read every line, including the parts this policy describes: what is sent
to a provider and when, that recordings are streamed through the server without
ever being written to a disk, how a credit account is deleted, and what is kept
afterwards. The App is licensed under the Apache License 2.0; you are free to
build it yourself rather than install ours.

Two honest limits, because a promise of transparency that overstates itself is
worth less than none:

- **Published source is not proof of what runs.** Reading `cloud/` tells you what
  the server is built from, not that the deployment matches it byte for byte.
  Nobody can verify that from outside — here or anywhere else.
- **Two things are deliberately not in the repository.** Credentials, which live
  in Cloudflare's secret store and are never committed, and one configuration
  file holding an alert address and the thresholds our abuse detection trips on.
  Publishing those would mean publishing exactly what someone would tune against.
  No behaviour described in this policy depends on them.

---

## 9. Data security

Data sent to AI providers, and to Dictate Cloud, is transmitted over encrypted
HTTPS connections. Your credentials and settings are kept in the App's private,
sandboxed storage, which on Android is not accessible to other apps. On the
Dictate Cloud server, recovery codes and access keys are stored only as SHA-256
hashes, so possession of the database does not allow anyone to sign in. No method
of transmission or storage is 100% secure.

---

## 10. Children's privacy

The App is not directed at children under the age of 13 (or the equivalent
minimum age in your jurisdiction), and we do not knowingly process data from
children. Note that the third-party AI providers may impose their own age
requirements.

---

## 11. International data transfers

The AI providers you choose may operate servers in other countries (for example
the United States). When you send audio or text to a provider, that data may be
processed in the country where the provider operates, under that provider's
policies.

For Dictate Cloud, the transfer arrangements are set out in section 4.3.

---

## 12. Your choices and rights

- You decide whether to use any AI provider and which one — or none, using
  on-device recognition.
- You can change or delete your API keys at any time in the App's settings.
- You can delete your dictation history, your custom prompts and reset settings
  in the App.
- You can delete your Dictate Cloud account (section 4.5).
- You can uninstall the App to remove all locally stored data.
- Under the GDPR you have the rights of access, rectification, erasure,
  restriction, data portability and objection (Art. 15–21), and the right to
  complain to a supervisory authority. For Dictate Cloud, please read section 4.6
  first: we can only act on a request we can connect to an account.
- For data already processed by a third-party provider, please exercise your
  rights directly with that provider, who is the controller for that data.

---

## 13. Changes to this policy

We may update this Privacy Policy from time to time. Material changes will be
reflected by updating the "Last updated" date above and publishing the new version
at this location. Continued use of the App after an update constitutes acceptance
of the revised policy.

---

## 14. Contact

If you have questions or requests regarding this Privacy Policy, contact:

**Jannis Zahn** (DevEmperor) — contact@devemperor.net
Talstraße 84, 35625 Hüttenberg, Germany

Project repository: https://github.com/DevEmperor/DictateKeyboard
