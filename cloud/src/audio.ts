/**
 * How long is the recording?
 *
 * This is the number the whole billing hangs on, so it is **not** taken from the client — and,
 * since it turned out to matter, not guessed either.
 *
 * Dictate records WAV (16 kHz, mono, 16 bit) and a WAV header states the duration outright. But the
 * app can also send a file the user picked from storage, and for those the length used to be
 * estimated from the file size at an assumed 32 kbit/s. That assumption is right for speech and
 * wrong for everything else, in both directions at once: a three-minute song at 192 kbit/s read as
 * eighteen minutes and was refused as too long, while a file encoded *below* the assumption read as
 * shorter than it is and would be charged short.
 *
 * Every common container states its duration in a header, and none of them needs decoding to reach
 * it — so the guessing is gone. What remains for an unrecognised format is the old estimate, now
 * used only for reserving credit, never for refusing.
 */

export interface AudioDuration {
  seconds: number;
  /** True when read from the file itself. Only then is billing exact and no correction needed. */
  exact: boolean;
}

/**
 * Reads the duration out of the file.
 *
 * Reads bytes rather than the whole file wherever the format allows: MP4 walks the top-level boxes
 * by their headers, Ogg needs only the tail. The file is already in memory — this is about not
 * copying twenty megabytes to learn a number that sits in the first hundred bytes.
 *
 * Returns null when the format is not one of these, which is the caller's signal to fall back.
 */
export async function probeDuration(file: Blob): Promise<AudioDuration | null> {
  const head = new Uint8Array(await file.slice(0, 8192).arrayBuffer());
  if (head.length < 12) return null;

  const wav = wavDuration(head, file.size);
  if (wav) return wav;

  const flac = flacDuration(head);
  if (flac !== null) return { seconds: flac, exact: true };

  if (ascii(head, 0, 4) === 'OggS') {
    const ogg = await oggDuration(file, head);
    return ogg === null ? null : { seconds: ogg, exact: true };
  }

  // `ftyp` is the first box of an MP4/M4A, but only by convention — walk the boxes either way.
  const mp4 = await mp4Duration(file);
  if (mp4 !== null) return { seconds: mp4, exact: true };

  const mp3 = mp3Duration(head, file.size);
  if (mp3 !== null) return { seconds: mp3, exact: true };

  return null;
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

/** FLAC states the sample count and rate in STREAMINFO, which is always the first block. */
function flacDuration(head: Uint8Array): number | null {
  if (ascii(head, 0, 4) !== 'fLaC' || head.length < 42) return null;
  // STREAMINFO body starts at 8 (4 magic + 4 block header). Sample rate is 20 bits at byte 10 of
  // the body, then 3 bits channels, 5 bits depth, then 36 bits of total samples.
  const b = head.subarray(8);
  const sampleRate = (b[10]! << 12) | (b[11]! << 4) | (b[12]! >> 4);
  const totalSamples =
    (b[13]! & 0x0f) * 2 ** 32 + (b[14]! << 24 >>> 0) + (b[15]! << 16) + (b[16]! << 8) + b[17]!;
  if (!sampleRate || !totalSamples) return null;
  return totalSamples / sampleRate;
}

/**
 * Ogg carries the duration only at the end: the last page's granule position is the sample count
 * so far. Opus counts in 48 kHz regardless of the source rate; Vorbis counts in its own.
 */
async function oggDuration(file: Blob, head: Uint8Array): Promise<number | null> {
  // 64 KB is comfortably more than one page, so the last page start is in there.
  const tailSize = Math.min(65_536, file.size);
  const tail = new Uint8Array(await file.slice(file.size - tailSize, file.size).arrayBuffer());

  let lastPage = -1;
  for (let i = tail.length - 27; i >= 0; i--) {
    if (tail[i] === 0x4f && tail[i + 1] === 0x67 && tail[i + 2] === 0x67 && tail[i + 3] === 0x53) {
      lastPage = i;
      break;
    }
  }
  if (lastPage < 0) return null;

  const view = new DataView(tail.buffer, tail.byteOffset, tail.byteLength);
  const granule = Number(view.getBigUint64(lastPage + 6, true));
  if (!granule || granule === 0xffff_ffff_ffff_ffff) return null;

  // Which codec decides what the granule counts in. Both identify themselves in the first page.
  const first = new TextDecoder('latin1').decode(head.subarray(0, 512));
  if (first.includes('OpusHead')) {
    const at = first.indexOf('OpusHead');
    // Pre-skip is priming samples that are decoded and thrown away — a few milliseconds, but the
    // arithmetic is free and it keeps short clips honest.
    const preSkip = new DataView(head.buffer, head.byteOffset, head.byteLength).getUint16(at + 10, true);
    return Math.max(0, granule - preSkip) / 48_000;
  }
  if (first.includes('vorbis')) {
    const at = first.indexOf('vorbis');
    const rate = new DataView(head.buffer, head.byteOffset, head.byteLength).getUint32(at + 11, true);
    return rate > 0 ? granule / rate : null;
  }
  return null;
}

/** MP4/M4A: `mvhd` inside `moov` holds a timescale and a duration in that scale. */
async function mp4Duration(file: Blob): Promise<number | null> {
  let offset = 0;
  for (let guard = 0; guard < 64 && offset + 8 <= file.size; guard++) {
    const header = new DataView(await file.slice(offset, offset + 16).arrayBuffer());
    if (header.byteLength < 8) return null;

    let boxSize = header.getUint32(0);
    let headerLen = 8;
    const type = String.fromCharCode(
      header.getUint8(4), header.getUint8(5), header.getUint8(6), header.getUint8(7),
    );
    if (!/^[a-zA-Z0-9 ]{4}$/.test(type)) return null;

    if (boxSize === 1) {
      if (header.byteLength < 16) return null;
      boxSize = Number(header.getBigUint64(8));
      headerLen = 16;
    } else if (boxSize === 0) {
      boxSize = file.size - offset;
    }
    if (boxSize < headerLen) return null;

    if (type === 'moov') {
      const moov = new Uint8Array(await file.slice(offset + headerLen, offset + boxSize).arrayBuffer());
      return mvhdWithin(moov);
    }
    offset += boxSize;
  }
  return null;
}

/** `mvhd` is a direct child of `moov`; scanning for its signature is enough and needs no recursion. */
function mvhdWithin(moov: Uint8Array): number | null {
  const view = new DataView(moov.buffer, moov.byteOffset, moov.byteLength);
  for (let i = 0; i + 32 <= moov.length; i++) {
    if (ascii(moov, i, 4) !== 'mvhd') continue;
    const body = i + 4;
    const version = moov[body]!;
    // v0: creation(4) modification(4) timescale(4) duration(4) — v1 widens the times to 8 bytes.
    const timescale = version === 1 ? view.getUint32(body + 20) : view.getUint32(body + 12);
    const duration = version === 1
      ? Number(view.getBigUint64(body + 24))
      : view.getUint32(body + 16);
    if (!timescale || !duration || duration === 0xffff_ffff) return null;
    return duration / timescale;
  }
  return null;
}

const MP3_RATES = [
  0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0,
];
const MP3_RATES_V2 = [0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0];
const MP3_SAMPLE_RATES: Record<number, number[]> = {
  3: [44100, 48000, 32000], // MPEG 1
  2: [22050, 24000, 16000], // MPEG 2
  0: [11025, 12000, 8000],  // MPEG 2.5
};

/**
 * MP3 has no container and therefore no single place that states the length.
 *
 * A variable-rate file usually carries a Xing or Info header in its first frame with the frame
 * count, which is exact. A constant-rate one does not, and there the frame header's bitrate against
 * the remaining bytes is exact enough — that is the arithmetic the format itself implies.
 */
function mp3Duration(head: Uint8Array, fileSize: number): number | null {
  let start = 0;
  if (ascii(head, 0, 3) === 'ID3' && head.length > 10) {
    // ID3v2 sizes are syncsafe: seven bits per byte.
    const tag = (head[6]! << 21) | (head[7]! << 14) | (head[8]! << 7) | head[9]!;
    start = 10 + tag;
  }

  // The first frame may not sit exactly at `start`; scan a little for the sync word.
  let frame = -1;
  for (let i = start; i + 4 <= Math.min(head.length, start + 4096); i++) {
    if (head[i] === 0xff && (head[i + 1]! & 0xe0) === 0xe0) { frame = i; break; }
  }
  if (frame < 0) return null;

  const b1 = head[frame + 1]!;
  const b2 = head[frame + 2]!;
  const versionBits = (b1 >> 3) & 0x03;
  const layerBits = (b1 >> 1) & 0x03;
  if (layerBits !== 0x01) return null; // Layer III only — that is what "MP3" means.

  const rates = versionBits === 3 ? MP3_RATES : MP3_RATES_V2;
  const bitrate = rates[(b2 >> 4) & 0x0f]! * 1000;
  const sampleRate = MP3_SAMPLE_RATES[versionBits]?.[(b2 >> 2) & 0x03] ?? 0;
  if (!bitrate || !sampleRate) return null;

  // Xing/Info sits after the side information, whose length depends on version and channel mode.
  // Rather than compute that, look for the signature in the frame — it is only ever there.
  const window = new TextDecoder('latin1').decode(head.subarray(frame, frame + 1024));
  const tagAt = Math.max(window.indexOf('Xing'), window.indexOf('Info'));
  if (tagAt >= 0) {
    const view = new DataView(head.buffer, head.byteOffset, head.byteLength);
    const at = frame + tagAt;
    const flags = view.getUint32(at + 4);
    if (flags & 0x0001) {
      const frames = view.getUint32(at + 8);
      const samplesPerFrame = versionBits === 3 ? 1152 : 576;
      if (frames > 0) return (frames * samplesPerFrame) / sampleRate;
    }
  }

  return ((fileSize - start) * 8) / bitrate;
}

/**
 * When the format is not one we can read: estimate generously upwards.
 *
 * 4000 bytes/s is 32 kbit/s — hardly any real speech recording sits below that. **Only for
 * reserving credit.** Deciding whether a file is too long is the other question and needs the
 * opposite bias — see [shortestPossibleSeconds].
 */
export function estimateSeconds(fileSize: number): AudioDuration {
  return { seconds: fileSize / 4000, exact: false };
}

/**
 * The shortest an unreadable file could possibly be, for the "is it too long" decision.
 *
 * The generous estimate is the wrong tool for refusing something: it assumes the *lowest* plausible
 * bitrate, so anything encoded better than speech looks longer than it is. 40000 bytes/s is
 * 320 kbit/s, past what anything short of lossless uses; a file below that bound *might* be within
 * the limit and is let through, and the real duration settles it afterwards.
 *
 * Only reached for formats [probeDuration] does not recognise, which is now a short list.
 */
export function shortestPossibleSeconds(fileSize: number): number {
  return fileSize / 40_000;
}

function ascii(bytes: Uint8Array, start: number, length: number): string {
  let out = '';
  for (let i = start; i < start + length && i < bytes.length; i++) {
    out += String.fromCharCode(bytes[i]!);
  }
  return out;
}
