/**
 * How long is the recording?
 *
 * This is the number the whole billing hangs on, so it is **not** taken from the client.
 * Dictate has recorded WAV since #130 (16 kHz, mono, 16 bit), and a WAV header states the
 * duration exactly — no claim from the app and no report from OpenAI required.
 *
 * For anything else (the user can also pick m4a, mp3 or a video from the file picker) there
 * is no reliable duration without decoding. Those deliberately reserve **too much** and are
 * corrected down once the answer arrives: better to hold too much for a moment than to bill
 * too little afterwards.
 */

export interface AudioDuration {
  seconds: number;
  /** True when computed from the WAV header — then the value is exact. */
  exact: boolean;
}

/**
 * Reads the duration from a WAV header.
 *
 * Takes the first bytes of the file (4 KB is ample) plus the total size, which serves as a
 * fallback: some writers leave the `data` chunk size at 0 or 0xFFFFFFFF because they did not
 * yet know the length while writing.
 */
export function wavDuration(head: Uint8Array, fileSize: number): AudioDuration | null {
  if (head.length < 12) return null;
  const view = new DataView(head.buffer, head.byteOffset, head.byteLength);
  if (ascii(head, 0, 4) !== 'RIFF' || ascii(head, 8, 4) !== 'WAVE') return null;

  let offset = 12;
  let byteRate = 0;
  let channels = 0;
  let sampleRate = 0;
  let bitsPerSample = 0;

  while (offset + 8 <= head.length) {
    const id = ascii(head, offset, 4);
    const size = view.getUint32(offset + 4, true);
    const body = offset + 8;

    if (id === 'fmt ' && body + 16 <= head.length) {
      channels = view.getUint16(body + 2, true);
      sampleRate = view.getUint32(body + 4, true);
      byteRate = view.getUint32(body + 8, true);
      bitsPerSample = view.getUint16(body + 14, true);
    } else if (id === 'data') {
      // The reliable route: the actual size of the payload.
      const declared = size;
      const remaining = Math.max(0, fileSize - body);
      const dataBytes = declared > 0 && declared !== 0xffff_ffff && declared <= remaining
        ? declared
        : remaining;
      const rate = byteRate > 0 ? byteRate : (sampleRate * channels * bitsPerSample) / 8;
      if (rate <= 0) return null;
      return { seconds: dataBytes / rate, exact: true };
    }

    // Chunks are padded to an even length.
    offset = body + size + (size % 2);
    if (size <= 0) break;
  }
  return null;
}

/**
 * When it is not a WAV: estimate generously upwards.
 *
 * 4000 bytes/s is 32 kbit/s — hardly any real speech recording sits below that. A typical
 * 64 kbit/s m4a is therefore estimated at roughly twice its length. That is the intent: too
 * much is reserved and corrected once the answer arrives.
 */
export function estimateSeconds(fileSize: number): AudioDuration {
  return { seconds: fileSize / 4000, exact: false };
}

function ascii(bytes: Uint8Array, start: number, length: number): string {
  let out = '';
  for (let i = start; i < start + length && i < bytes.length; i++) {
    out += String.fromCharCode(bytes[i]!);
  }
  return out;
}
