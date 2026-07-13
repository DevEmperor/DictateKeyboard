const heights = [8, 17, 25, 13, 31, 20, 11, 27, 18, 9, 24, 15, 29, 12, 21, 7, 19, 25];

export function Waveform({ active = true, compact = false }) {
  return (
    <span className={`waveform ${active ? "is-active" : ""} ${compact ? "is-compact" : ""}`} aria-hidden="true">
      {heights.map((height, index) => (
        <span
          className="waveform-bar"
          key={`${height}-${index}`}
          style={{ "--bar-height": `${height}px`, "--bar-delay": `${index * -47}ms` }}
        />
      ))}
    </span>
  );
}
