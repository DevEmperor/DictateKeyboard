import { CaretDown } from "@phosphor-icons/react";

const questions = [
  {
    question: "Does Dictate Keyboard require a subscription?",
    answer: "No. Dictate Keyboard has no monthly subscription and never has. Its source is available under Apache 2.0, offline transcription uses no cloud API, and any cloud provider you configure bills its own API usage directly. Optional Dictate Cloud credit is prepaid and one-off — you buy a pack of minutes, nothing renews.",
  },
  {
    question: "Does Dictate Keyboard work offline?",
    answer: "Yes. Download a supported Whisper or Parakeet model and transcription can run entirely on your Android device without a connection. Rewording, model downloads, and cloud transcription still require the relevant network service.",
  },
  {
    question: "Do I need an API key?",
    answer: "On-device transcription needs no cloud API key after its model has been downloaded. Hosted providers normally require your own key. Self-hosted or custom routes can differ, so follow the setup for the endpoint you configure.",
  },
  {
    question: "Where does my audio go?",
    answer: "In offline mode, audio stays on the device. With your own provider key or your own server, it goes straight to that endpoint and through no machine of ours, unless you deliberately configure an HTTP/SOCKS proxy. The one exception is the optional prepaid credit route, which passes through the Dictate Cloud server — it stores neither the audio nor the transcript, and its source is in the same public repository. Provider policies apply. Local history and audio retention are configurable inside the app.",
  },
  {
    question: "What is Dictate Cloud, and what does it store?",
    answer: "An optional way in for people who would rather not open a provider account: buy prepaid minutes on Google Play and dictate straight away. It is the only route that passes through a server we run, and that server writes neither your audio nor your text to disk — it forwards them and returns the answer, keeping only figures like duration, token counts and a status code. An account holds a wallet and a recovery code, no name or email address, and you can delete it from inside the app. The server is one Cloudflare Worker whose source sits in the app repository under cloud/.",
  },
  {
    question: "Can I keep my current keyboard?",
    answer: "Yes. Use Dictate Keyboard as your complete keyboard, or enable its optional floating button while another keyboard remains active. The floating mode uses Android’s Accessibility Service and is off by default.",
  },
  {
    question: "How is this different from subscription dictation apps?",
    answer: "Dictate Keyboard is Android-first and open source, offers downloaded offline models, works as a complete keyboard, and lets you choose the provider behind it — including none at all. There is no monthly plan: you either pay a provider for what you use, buy a one-off pack of minutes, or run a model on the device for nothing.",
  },
  {
    question: "Can I dictate long recordings and use Wear OS?",
    answer: "Yes. Long-form mode transcribes recordings in background segments, and Dictate Keyboard also supports Wear OS 3+ through the paired phone or in standalone mode.",
  },
];

export function Faq() {
  return (
    <div className="faq-list">
      {questions.map((item, index) => (
        <details key={item.question} name="dictate-faq">
          <summary>
            <span className="faq-number">0{index + 1}</span>
            <span>{item.question}</span>
            <CaretDown className="faq-caret" size={20} weight="bold" aria-hidden="true" />
          </summary>
          <div className="faq-answer"><p>{item.answer}</p></div>
        </details>
      ))}
    </div>
  );
}
