# Traditional Chinese (`zh-TW`) UI Localization Guide

This document summarizes the Traditional Chinese (Taiwan) localization added in this contribution and provides a compact glossary + prompt rule set so `values-zh-rTW` can be kept in sync inside DictateKeyboard's existing AI batch localization workflow with zero manual upkeep.

---

## 1. Why `values-zh-rTW` Matters (Two Layers)

### Layer 1 — Android Resource Resolution (no Kotlin changes)

DictateKeyboard ships `values-zh-rCN/` (Simplified Chinese), while `projectMinSdk=26` (≥ API 24). Because Android 7.0+ uses script-aware locale resolution:

```text
Device locale zh-TW  →  zh-Hant-TW
  → values-zh-rTW      (missing upstream)
  → values-b+zh+Hant   (missing upstream)
  → values-zh-Hant     (missing upstream)
  → values-zh          (missing upstream)
  → values/            → English fallback
```

Because `values-zh-rCN` carries implicit script `Hans`, Taiwan devices (`zh-Hant-TW`) do **not** fall back to `values-zh-rCN` — they fall back to English. Adding `values-zh-rTW/strings.xml` follows the repo's existing `r<REGION>` directory convention (`zh-rCN`, `ko-rKR`, `pt-rBR`, `lv-rLV`) and immediately enables Traditional Chinese for users whose system locale is `zh-TW`.

### Layer 2 — In-App Language Picker (1-line Kotlin change)

`OtherScreen.kt` lists 40 language tags (ending with `"zh-CN"`). Adding `"zh-TW"` right after `"zh-CN"` exposes **中文 (台灣)** in **Settings → Other → Settings language**, allowing users to switch immediately regardless of device locale.

---

## 2. Scope of Changes

- `app/src/main/res/values-zh-rTW/strings.xml`
  - Translated directly from the English base (`app/src/main/res/values/strings.xml`), **not** machine-converted from `values-zh-rCN`.
  - Aligned 100% with the base resource keys (`2547` `<string>` + `11` `<plurals>`), preserving all `1618` `comment="..."` translation hints, all placeholders (`{...}`, `%1$s`, etc.), and inline `<b>`/`<i>` markup.
- `wear/src/main/res/values-zh-rTW/strings.xml`
  - Aligned 100% with `wear/src/main/res/values/strings.xml` (`69` `<string>` entries).
  - Note: the `wear` module does not override `Locale` / `Configuration`, so Wear OS UI strings follow the watch's system locale rather than the phone app's in-app language picker.
- `app/src/main/kotlin/dev/patrickgold/florisboard/app/settings/advanced/OtherScreen.kt`
  - 1-line addition: `"zh-TW"` added after `"zh-CN"`.
- `values/strings_dont_translate.xml` is untouched.

---

## 3. Keeping `zh-TW` in Sync (AI Batch Prompt + Taiwan Glossary)

To prevent static translation drift when `values/strings.xml` changes, you can add `values-zh-rTW` to your existing English → locale batch prompt with the following rules and canonical glossary.

### Prompt Rules for `zh-TW`

- **Source**: Translate directly from English `values/strings.xml` and follow `comment="..."` length/tone guidance. Do **not** convert from `zh-rCN`.
- **Preserve**: Keep `comment="..."` attributes, all placeholders (`{app_name}`, `%1$s`, `%2$d`, etc.), `\n`, `\'`, and inline `<b>`/`<i>` tags intact.
- **Keep in English**: `Dictate`, `Dictate (Wear)`, `Dictate Cloud`, `Wear OS`, `Groq`, `OpenAI`, `Gemini`, `Claude`, `Whisper`, `Parakeet`, `OpenRouter`, `Soniox`, `Deepgram`, `AssemblyAI`, `ElevenLabs`, `Aurora`, `Lattice`, `AMOLED`, `Material You`, `GIF`, `API`, `dp`.
- **Register**: Concise, natural Taiwan Traditional Chinese (台灣繁體中文). Never use Simplified Chinese characters or Mainland Chinese technical terms.

### Canonical English → Taiwan Traditional Chinese Glossary

| English | Taiwan Traditional Chinese (`zh-TW`) | Avoid (Mainland `zh-CN` / converted) |
|---|---|---|
| Settings | 設定 | 设置 / 設置 |
| Clipboard | 剪貼簿 | 剪贴板 / 剪貼板 |
| Folder | 資料夾 | 文件夹 / 文件夾 |
| Smart / Smartbar | 智慧 / 智慧列 | 智能 / 智能栏 |
| Default | 預設 | 默认 / 默認 |
| Import / Export | 匯入 / 匯出 | 导入 / 导出 / 導入 / 導出 |
| Theme | 佈景主題 | 主题 |
| User | 使用者 | 用户 / 用戶 |
| Font / Screen | 字型 / 螢幕 | 字体 / 屏幕 / 字體 |
| Feedback / Information | 意見回饋 / 資訊 | 反馈 / 信息 / 反饋 |
| Network / Server | 網路 / 伺服器 | 网络 / 服务器 / 網絡 / 服務器 |
| Account / Sign in | 帳號 / 登入 | 账号 / 登录 / 賬號 / 登錄 |
| Software / Hardware | 軟體 / 硬體 | 软件 / 硬件 / 軟件 |
| Memory / Cache / Database | 記憶體 / 快取 / 資料庫 | 内存 / 缓存 / 数据库 / 內存 / 緩存 / 數據庫 |
| Optimize | 最佳化 | 优化 / 優化 |
| Audio / Video | 音訊 / 影片 | 音频 / 视频 / 音頻 / 視頻 |
| Text / Character / String | 文字 / 字元 / 字串 | 文本 / 字符 / 字符串 |
| Real-time / Live / Online / Offline | 即時 / 線上 / 離線 | 实时 / 在线 / 离线 / 實時 / 在線 |
| Local / On-device | 本機 / 裝置端 | 本地 / 设备端 |
| Long-press | 長按 | 长按 / 長壓 |
| Dictation / Transcription | 語音輸入 / 轉錄 | 听写 / 转写 |
| Rewording / Provider / Prompt | 改寫 / 服務商 / 提示 | 重写 / 提供商 / 提示词 |
| Floating button | 浮動按鈕 | 悬浮按钮 |
| Custom words / Subtype | 自訂詞彙 / 子類型 | 自定义词汇 / 子类型 |
