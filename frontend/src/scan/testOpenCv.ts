import { createRequire } from "node:module";
import type { OpenCv } from "./opencv";

/**
 * OpenCV.js for unit tests in Node, loaded through Node's own require: a dynamic import never settles because the
 * module is thenable (see openCvReady).
 */
export function testOpenCv(): Promise<OpenCv> {
  const cv = createRequire(import.meta.url)("@techstark/opencv-js") as OpenCv & { onRuntimeInitialized?: () => void };
  return new Promise((resolve) => {
    if (typeof cv.Mat === "function") {
      resolve(wrap(cv));
    } else {
      cv.onRuntimeInitialized = () => resolve(wrap(cv));
    }
  });
}

/** The module object is thenable; a plain object without `then` can be resolved safely. */
function wrap(cv: OpenCv): OpenCv {
  return new Proxy(cv, { get: (target, key) => (key === "then" ? undefined : Reflect.get(target, key)) });
}
