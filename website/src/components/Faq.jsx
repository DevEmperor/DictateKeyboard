import { CaretDown } from "@phosphor-icons/react";

const questions = [
  {
    question: "Is Dictate free?",
    answer: "Yes. Dictate is free and open source under Apache 2.0, including the official Google Play build. Offline transcription uses no cloud API. If you connect a paid cloud provider, that provider bills its own API usage directly.",
  },
  {
    question: "Does Dictate work offline?",
    answer: "Yes. Download a supported Whisper or Parakeet model and transcription can run entirely on your Android device without a connection. Rewording, model downloads, and cloud transcription still require the relevant network service.",
  },
  {
    question: "Do I need an API key?",
    answer: "On-device transcription needs no cloud API key after its model has been downloaded. Hosted providers normally require your own key. Self-hosted or custom routes can differ, so follow the setup for the endpoint you configure.",
  },
  {
    question: "Where does my audio go?",
    answer: "In offline mode, audio stays on the device. In cloud mode, it goes to the provider you selected—not through a Dictate developer server—unless you deliberately configure an HTTP/SOCKS proxy. Provider policies apply. Local history and audio retention are configurable inside the app.",
  },
  {
    question: "Can I keep my current keyboard?",
    answer: "Yes. Use Dictate as your complete keyboard, or enable its optional floating button while another keyboard remains active. The floating mode uses Android’s Accessibility Service and is off by default.",
  },
  {
    question: "How is this different from subscription dictation apps?",
    answer: "Dictate is Android-first and open source, offers downloaded offline models, works as a complete keyboard, and lets you choose the provider behind it. It trades a hosted cross-platform account for more local control and Android-native flexibility.",
  },
  {
    question: "Can I dictate long recordings and use Wear OS?",
    answer: "Yes. Long-form mode transcribes recordings in background segments, and Dictate also supports Wear OS 3+ through the paired phone or in standalone mode.",
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
