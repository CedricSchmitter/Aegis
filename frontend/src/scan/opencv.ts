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

/**
 * Waits until the OpenCV.js runtime (WebAssembly) is initialized. The module object has a `then` method, so it must
 * never be awaited or returned from a promise (that never settles), and must not be loaded through a dynamic
 * `import()`: the bundler exposes `then` on the module namespace as well. It is imported statically by the scan
 * worker, which itself is only started for the first page, and handed out wrapped here.
 */
export function openCvReady(module: unknown): Promise<{ cv: OpenCv }> {
  const cv = module as OpenCv & { onRuntimeInitialized?: () => void };
  return new Promise((resolve) => {
    if (typeof cv.Mat === "function") {
      resolve({ cv });
    } else {
      cv.onRuntimeInitialized = () => resolve({ cv });
    }
  });
}
