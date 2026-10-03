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
  // Sheet, corner detection and saving; the list while the archive and the classification run; then the result.
  upload: [
    { from: 0.3, to: 6.6, rate: 1.6 },
    { from: 8.0, to: 18.8, rate: 5.1 },
    { from: 19.3, to: 23.5, rate: 2.1 },
  ] as Segment[],
  // Open the editor and split; turn the sideways page and rename; the result.
  pages: [
    { from: 0.8, to: 5.0, rate: 2.1 },
    { from: 5.2, to: 8.2, rate: 2.5 },
    { from: 11.8, to: 13.2, rate: 1.75 },
  ] as Segment[],
  today: { a: 0.3, rate: 1.0, phone: 0.8 },
  chat: { a: 0.8, rate: 2.2, zoomAt: 5.3 },
  phoneChat: { a: 2.0, rate: 3.5 },
  montage: [
    { src: "desk-search.mp4", startFrom: 0.3, rate: 3, tag: "VOLLTEXTSUCHE", zoomOrigin: "40% 15%" },
    { src: "desk-theme.mp4", startFrom: 0.4, rate: 3, tag: "3 FARBTHEMEN", zoomOrigin: "30% 60%" },
    { src: "desk-today.mp4", startFrom: 3.6, rate: 2.5, tag: "VERTRÄGE MIT BELEG", zoomOrigin: "50% 40%" },
    { src: "desk-documents.mp4", startFrom: 1.0, rate: 3, tag: "ORDNER & LABELS", zoomOrigin: "60% 30%" },
  ],
  documents: { a: 0.3 },
};
