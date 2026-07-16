import { DownloadSimple, GithubLogo, Lock, Star } from "@phosphor-icons/react";
import { Reveal } from "./Reveal";
import { CountUp } from "./CountUp";

const PLAY_URL = "https://play.google.com/store/apps/details?id=net.devemperor.dictate";
const GITHUB_URL = "https://github.com/DevEmperor/DictateKeyboard";

// Verifiable proof points, pulled live from Google Play + the GitHub API (2026-07-16). Re-check before big
// pushes — Play figures drift over time.
const stats = [
  { icon: Star, value: "4.8", label: "Google Play rating", href: PLAY_URL, note: "603 ratings" },
  { icon: GithubLogo, value: "240", label: "GitHub stars", href: GITHUB_URL, note: "open source" },
  { icon: DownloadSimple, value: "1K+", label: "installs", href: PLAY_URL, note: "on Google Play" },
  { icon: Lock, value: "€0", label: "subscription", href: `${GITHUB_URL}/blob/main/LICENSE`, note: "Apache 2.0" },
];

// Real, verbatim excerpts from featured 5★ Google Play reviews (2026). Contiguous quotes, nothing altered.
const reviews = [
  {
    quote: "It’s insanely cheap when you’re paying for straight API usage via the key. I would highly recommend it. It’s very stable, super reliable.",
    author: "Thomas Cahill",
    rating: 5,
    source: "Google Play · June 2026",
  },
  {
    quote: "I’m actually using it right now to write this review, and I didn’t have to touch a single key, and it only took me less than 40 seconds.",
    author: "Nicholas Rinaldi",
    rating: 5,
    source: "Google Play · June 2026",
  },
  {
    quote: "OpenAI Whisper is exceptional, especially with double language. It easily switches back and forth, and the error rate is much lower than on any other keyboards.",
    author: "Max Rempel",
    rating: 5,
    source: "Google Play · June 2026",
  },
];

export function SocialProof() {
  const shownStats = stats.filter((item) => item.value !== null);
  return (
    <section className="social-proof section-shell" id="loved">
      <div className="social-intro section-intro">
        <Reveal>
          <span className="eyebrow">TRUSTED IN THE OPEN</span>
          <h2>Real code. Real users.<br /><span>No lock-in to take on faith.</span></h2>
        </Reveal>
        <Reveal delay={0.08}>
          <p>Everything that matters is verifiable: the source is public, the rating is on Google Play, and there is no account or subscription to walk away from.</p>
        </Reveal>
      </div>

      <div className="social-stats">
        {shownStats.map((item, index) => {
          const Icon = item.icon;
          return (
            <Reveal className="social-stat" delay={index * 0.05} key={item.label}>
              <a href={item.href} target="_blank" rel="noreferrer">
                <span className="social-stat-icon"><Icon size={19} weight="bold" aria-hidden="true" /></span>
                <strong><CountUp value={item.value} /></strong>
                <span className="social-stat-label">{item.label}</span>
                <small>{item.note}</small>
              </a>
            </Reveal>
          );
        })}
      </div>

      {reviews.length > 0 && (
        <div className="social-reviews">
          {reviews.map((review, index) => (
            <Reveal className="social-review" delay={(index % 3) * 0.05} key={review.author}>
              <div className="social-review-stars" aria-label={`${review.rating} out of 5 stars`}>
                {Array.from({ length: 5 }).map((_, i) => (
                  <Star key={i} size={14} weight="fill" aria-hidden="true" className={i < review.rating ? "is-full" : "is-empty"} />
                ))}
              </div>
              <p>{review.quote}</p>
              <div className="social-review-meta">
                <strong>{review.author}</strong>
                <small>{review.source}</small>
              </div>
            </Reveal>
          ))}
        </div>
      )}
    </section>
  );
}
