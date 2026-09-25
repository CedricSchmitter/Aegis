import openCvModule from "@techstark/opencv-js";
import { openCvReady } from "./opencv";
import { processPage, type PageSettings } from "./process";
import type { ProcessFailure, ProcessRequest, ProcessResponse } from "./processor";

/**
 * Turns a photo into a page off the main thread, so the app stays responsive while OpenCV works. The photo is
 * decoded upright (its EXIF orientation applied), reduced to a bounded size, processed and encoded as JPEG.
 */
// The app compiles against the DOM library; these are the two worker members used here.
const scope = self as unknown as {
  onmessage: ((event: MessageEvent<ProcessRequest>) => void) | null;
  postMessage(message: ProcessResponse): void;
};

const ready = openCvReady(openCvModule);

/** Long side a photo is decoded at; bounds memory for 50-megapixel cameras while keeping A4 at 300 dpi possible. */
const MAX_SOURCE_PIXELS = 4096;
const THUMBNAIL_PIXELS = 320;
/** JPEG quality of a page: text stays sharp for OCR at about 0.3 to 1 MB per A4 page. */
const PAGE_QUALITY = 0.85;

scope.onmessage = async (event) => {
  const { id, photo, settings } = event.data;
  try {
    scope.postMessage(await handle(id, photo, settings));
  } catch (failure) {
    const reason: ProcessFailure["reason"] = failure instanceof DecodeError ? "decode" : "process";
    scope.postMessage({ id, ok: false, reason });
  }
};

class DecodeError extends Error {}

async function handle(id: number, photo: Blob, settings: PageSettings): Promise<ProcessResponse> {
  const source = await decode(photo);
  const { cv } = await ready;
  const mat = cv.matFromImageData(source);
  try {
    const result = processPage(cv, mat, settings);
    try {
      const { cols: width, rows: height } = result.image;
      const pixels = new ImageData(new Uint8ClampedArray(result.image.data), width, height);
      const page = await encode(pixels, width, height, PAGE_QUALITY);
      const scale = Math.min(1, THUMBNAIL_PIXELS / Math.max(width, height));
      const thumbnail = await encode(pixels, width, height, 0.7, scale);
      return { id, ok: true, page, thumbnail, width, height, mode: result.mode, corners: result.corners };
    } finally {
      result.image.delete();
    }
  } finally {
    mat.delete();
  }
}

async function decode(photo: Blob): Promise<ImageData> {
  let bitmap: ImageBitmap;
  try {
    bitmap = await createImageBitmap(photo, { imageOrientation: "from-image" });
  } catch {
    // HEIC and other formats the browser cannot open.
    throw new DecodeError();
  }
  const scale = Math.min(1, MAX_SOURCE_PIXELS / Math.max(bitmap.width, bitmap.height));
  const width = Math.max(1, Math.round(bitmap.width * scale));
  const height = Math.max(1, Math.round(bitmap.height * scale));
  const canvas = new OffscreenCanvas(width, height);
  const context = canvas.getContext("2d", { willReadFrequently: true });
  if (!context) throw new Error("no 2d context");
  context.imageSmoothingQuality = "high";
  context.drawImage(bitmap, 0, 0, width, height);
  bitmap.close();
  return context.getImageData(0, 0, width, height);
}

async function encode(pixels: ImageData, width: number, height: number, quality: number, scale = 1): Promise<Blob> {
  const full = new OffscreenCanvas(width, height);
  full.getContext("2d")!.putImageData(pixels, 0, 0);
  if (scale === 1) return full.convertToBlob({ type: "image/jpeg", quality });
  const small = new OffscreenCanvas(Math.max(1, Math.round(width * scale)), Math.max(1, Math.round(height * scale)));
  const context = small.getContext("2d")!;
  context.imageSmoothingQuality = "high";
  context.drawImage(full, 0, 0, small.width, small.height);
  return small.convertToBlob({ type: "image/jpeg", quality });
}
