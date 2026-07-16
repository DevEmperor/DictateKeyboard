/*
 * Official-style Google Play glyph (the four-colour triangle) plus an optional full "Get it on Google Play"
 * badge. Drawn inline so it stays crisp and needs no external asset. Colours are fixed brand colours and do
 * not theme-invert — the Play triangle is a trademark and must keep its palette on both grounds.
 */

export function GooglePlayGlyph({ size = 20, className = "" }) {
  return (
    <svg className={className} width={size} height={size} viewBox="0 0 512 512" aria-hidden="true" focusable="false">
      <path d="M48 42c-6 4-10 12-10 22v380c0 10 4 18 10 22l2 2 213-213v-4L50 40l-2 2Z" fill="#00D2FF" />
      <path d="M336 336 263 263v-4l73-73 2 1 87 49c25 14 25 37 0 51l-87 49-2 0Z" fill="#FFCE00" />
      <path d="M338 335 263 260 48 475c8 9 22 10 37 1l253-141Z" fill="#F43249" />
      <path d="M338 185 85 44C70 35 56 36 48 45l215 215 75-75Z" fill="#00F076" />
    </svg>
  );
}

export function GooglePlayBadge({ className = "", href, ...rest }) {
  const content = (
    <span className={`gplay-badge ${className}`}>
      <GooglePlayGlyph size={26} />
      <span className="gplay-badge-copy">
        <small>GET IT ON</small>
        <strong>Google Play</strong>
      </span>
    </span>
  );
  if (href) {
    return (
      <a href={href} className="gplay-badge-link" {...rest}>
        {content}
      </a>
    );
  }
  return content;
}
