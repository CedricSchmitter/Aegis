// Where each scene's recording is cut (seconds in the source recording) and how fast each part plays.
export const PROMO_SECONDS = 56;

export interface Segment {
  from: number;
  to: number;
  rate: number;
}

/** Length of a run of segments in seconds of the final video. */
export const segmentsLength = (segments: Segment[]) => segments.reduce((sum, s) => sum + (s.to - s.from) / s.rate, 0);

export const CUTS = {
  // Sheet, corner detection and saving; then the list while the archive and the classification run; then the result.
  upload: [
    { from: 0.3, to: 6.6, rate: 1.35 },
    { from: 8.0, to: 18.8, rate: 3.2 },
    { from: 19.3, to: 24.6, rate: 2.1 },
  ] as Segment[],
  today: { a: 0.3, rate: 1.0, phone: 0.8 },
  chat: { a: 0.8, rate: 1.6, zoomAt: 7.6 },
  documents: { a: 0.3 },
};
