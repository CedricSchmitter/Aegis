import type * as OpenCvModule from "@techstark/opencv-js";

/** The OpenCV.js API object. */
export type OpenCv = typeof OpenCvModule;
export type Mat = OpenCvModule.Mat;

/** Anything OpenCV allocates in WebAssembly memory; it is not garbage collected and must be deleted. */
interface Deletable {
  delete(): void;
}

/**
 * Runs `work` and deletes every object it registered with `keep`, also when it throws. A result that should
 * outlive the call is simply not registered.
 */
export function withMats<T>(work: (keep: <M extends Deletable>(value: M) => M) => T): T {
  const owned: Deletable[] = [];
  try {
    return work((value) => {
      owned.push(value);
      return value;
    });
  } finally {
    owned.reverse().forEach((value) => value.delete());
  }
}

let loading: Promise<{ cv: OpenCv }> | null = null;

/**
 * Loads OpenCV.js (about 10 MB) on first use only, so the app itself stays small. The module object is thenable,
 * so it is handed out wrapped: resolving a promise with it directly would never settle.
 */
export function loadOpenCv(): Promise<{ cv: OpenCv }> {
  loading ??= import("@techstark/opencv-js").then(
    (imported) =>
      new Promise<{ cv: OpenCv }>((resolve) => {
        const cv = (imported.default ?? imported) as unknown as OpenCv & { onRuntimeInitialized?: () => void };
        if (typeof cv.Mat === "function") {
          resolve({ cv });
        } else {
          cv.onRuntimeInitialized = () => resolve({ cv });
        }
      }),
  ).catch((failure: unknown) => {
    // A failed download (for example a lost connection) may be retried with the next page.
    loading = null;
    throw failure;
  });
  return loading;
}
