import type { Quad } from "./geometry";
import type { PageMode, PageSettings } from "./process";

export interface ProcessRequest {
  id: number;
  photo: Blob;
  settings: PageSettings;
}

export interface ProcessedResult {
  /** The page as JPEG, as it goes into the PDF. */
  page: Blob;
  /** A small JPEG of the page for the page list. */
  thumbnail: Blob;
  width: number;
  height: number;
  mode: PageMode;
  corners: Quad | null;
}

export interface ProcessFailure {
  id: number;
  ok: false;
  /** `decode`: the browser cannot open the photo (e.g. HEIC); `process`: anything else went wrong. */
  reason: "decode" | "process";
}

export type ProcessResponse = ({ id: number; ok: true } & ProcessedResult) | ProcessFailure;

export class ProcessingError extends Error {
  constructor(readonly reason: ProcessFailure["reason"]) {
    super(`page processing failed: ${reason}`);
  }
}

/**
 * Main-thread side of the scan worker. Pages are processed one after another: several full-size photos decoded at
 * once could exhaust a phone's memory. The worker, and with it OpenCV, is only started for the first page.
 */
export class PageProcessor {
  private worker: Worker | null = null;
  private nextId = 1;
  private readonly waiting = new Map<number, { resolve: (result: ProcessedResult) => void; reject: (e: Error) => void }>();
  private queue: Promise<unknown> = Promise.resolve();

  process(photo: Blob, settings: PageSettings): Promise<ProcessedResult> {
    const run = () => this.send(photo, settings);
    const result = this.queue.then(run, run);
    this.queue = result.catch(() => undefined);
    return result;
  }

  dispose() {
    this.worker?.terminate();
    this.worker = null;
    this.waiting.forEach(({ reject }) => reject(new ProcessingError("process")));
    this.waiting.clear();
  }

  private send(photo: Blob, settings: PageSettings): Promise<ProcessedResult> {
    const worker = this.start();
    const id = this.nextId++;
    return new Promise((resolve, reject) => {
      this.waiting.set(id, { resolve, reject });
      worker.postMessage({ id, photo, settings } satisfies ProcessRequest);
    });
  }

  private start(): Worker {
    if (this.worker) return this.worker;
    const worker = new Worker(new URL("./scan.worker.ts", import.meta.url), { type: "module" });
    worker.onmessage = (event: MessageEvent<ProcessResponse>) => {
      const response = event.data;
      const waiting = this.waiting.get(response.id);
      if (!waiting) return;
      this.waiting.delete(response.id);
      if (response.ok) {
        const { page, thumbnail, width, height, mode, corners } = response;
        waiting.resolve({ page, thumbnail, width, height, mode, corners });
      } else {
        waiting.reject(new ProcessingError(response.reason));
      }
    };
    // A worker that fails to start or crashes (for example out of memory) fails every waiting page; the next
    // page starts a new worker.
    worker.onerror = () => {
      this.worker = null;
      worker.terminate();
      this.waiting.forEach(({ reject }) => reject(new ProcessingError("process")));
      this.waiting.clear();
    };
    this.worker = worker;
    return worker;
  }
}
