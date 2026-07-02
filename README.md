# DictateKeyboard — Community Prompt Library

This branch hosts the **community prompt library** that [DictateKeyboard](https://github.com/DevEmperor/DictateKeyboard)
fetches inside the app (**Settings → Dictate → Prompts → ⋮ → Browse community library**).

It is a plain, static JSON file — there is no server. The app downloads [`library.json`](library.json)
over HTTPS, caches it on the device, and lets the user import any prompt into their own list.

## What's in a prompt

Every entry in `library.json` looks like this:

```json
{
  "id": "fix-grammar",
  "name": "Fix Grammar",
  "prompt": "Correct all spelling, grammar and punctuation mistakes ...",
  "requiresSelection": true,
  "autoApply": false,
  "category": "Editing",
  "language": "en",
  "author": "your-github-handle",
  "description": "Fixes spelling, grammar and punctuation without changing your wording."
}
```

| Field | Required | Meaning |
|-------|----------|---------|
| `id` | ✅ | Unique lowercase slug (`^[a-z0-9]+(-[a-z0-9]+)*$`). Used as the de-dup key. |
| `name` | ✅ | Short display name (≤ 60 chars). |
| `prompt` | ✅ | The instruction sent to the AI model (≤ 4000 chars). |
| `requiresSelection` | | `true` = operates on the selected text and replaces it. Default `true`. |
| `autoApply` | | `true` = suggested to run automatically after each transcription. Default `false`. |
| `category` | | Grouping shown as a filter chip, e.g. `Editing`, `Tone`, `Length`, `Format`, `Translation`, `Fun`. |
| `language` | | Language the prompt targets, e.g. `en`. Omit for language-agnostic prompts. |
| `author` | | Filled automatically on merge with your GitHub handle — you don't need to set it. |
| `description` | | One line shown under the name (≤ 160 chars). |

A good prompt is **self-contained** and tells the model to *return only the result* (no quotation marks,
no explanations), so its output can be committed straight into the text field.

## How to contribute

### From the app (easiest)
Open any of your own prompts in DictateKeyboard, tap **Share to community**, and confirm. GitHub opens
with the prompt pre-filled as a submission file under `submissions/` — sign in and press
*Propose new file* to open a pull request. (GitHub forks the repo for you automatically.)

### By hand
1. Fork this repo and switch to the `prompt-library` branch.
2. Drop a `{ "version": 1, "prompts": [ … ] }` file into `submissions/`.
3. Make sure your JSON validates against [`schema.json`](schema.json) and your `id` is unique.
4. Open a pull request.

### What happens on merge
Submissions are **reviewed by a maintainer before merging** — that review is the gate; nothing lands
automatically. Once a maintainer merges your PR, a GitHub Action ([`merge-submissions.yml`](.github/workflows/merge-submissions.yml))
folds your prompt into `library.json`, **stamps the `author` field with your GitHub handle
automatically**, and removes the submission file. So you never have to fill in an author yourself.

Keep prompts useful, safe, and free of personal data.
