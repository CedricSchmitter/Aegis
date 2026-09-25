import { limitedSize, rectifiedSize, scaleQuad, type Quad } from "./geometry";
import { withMats, type Mat, type OpenCv } from "./opencv";

/**
 * Straightens the sheet inside `corners` (relative to the photo) into an upright rectangle. Parts of a corrected
 * corner that lie outside the photo become white, like the margin of a scan.
 */
export function rectify(cv: OpenCv, rgba: Mat, corners: Quad): Mat {
  return withMats((keep) => {
    const pixels = scaleQuad(corners, rgba.cols, rgba.rows);
    const size = rectifiedSize(pixels);
    const from = keep(cv.matFromArray(4, 1, cv.CV_32FC2, pixels.flatMap((p) => [p.x, p.y])));
    const to = keep(
      cv.matFromArray(4, 1, cv.CV_32FC2, [0, 0, size.width, 0, size.width, size.height, 0, size.height]),
    );
    const transform = keep(cv.getPerspectiveTransform(from, to));
    const page = new cv.Mat();
    cv.warpPerspective(
      rgba,
      page,
      transform,
      new cv.Size(size.width, size.height),
      cv.INTER_LINEAR,
      cv.BORDER_CONSTANT,
      new cv.Scalar(255, 255, 255, 255),
    );
    return page;
  });
}

/** The whole photo, only reduced to the page size limit. */
export function limit(cv: OpenCv, rgba: Mat): Mat {
  const size = limitedSize(rgba.cols, rgba.rows);
  const page = new cv.Mat();
  if (size.width === rgba.cols && size.height === rgba.rows) {
    rgba.copyTo(page);
  } else {
    cv.resize(rgba, page, new cv.Size(size.width, size.height), 0, 0, cv.INTER_AREA);
  }
  return page;
}

/** Long side of the background estimate; small enough that one text stroke is at most a pixel or two wide. */
const BACKGROUND_PIXELS = 400;
/** Level that counts as paper after the background is removed; lighter noise and grain become pure white. */
const PAPER_WHITE = 235;
/** Darkens everything below paper white by this factor after the background is removed, for crisper text. */
const INK_GAIN = 1.3;

/**
 * The "scan" look: shadows, uneven light and the paper tint are divided out per colour channel, so the paper becomes
 * white while ink, stamps and signatures keep their colours; then the remaining ink is darkened. The background is
 * estimated on a reduced copy (dilation removes the text, a median smooths the light) for speed on a phone.
 */
export function whiten(cv: OpenCv, rgba: Mat): Mat {
  return withMats((keep) => {
    const rgb = keep(new cv.Mat());
    cv.cvtColor(rgba, rgb, cv.COLOR_RGBA2RGB);
    const channels = keep(new cv.MatVector());
    cv.split(rgb, channels);
    const scale = Math.min(1, BACKGROUND_PIXELS / Math.max(rgb.cols, rgb.rows));
    const smallSize = new cv.Size(Math.max(1, Math.round(rgb.cols * scale)), Math.max(1, Math.round(rgb.rows * scale)));
    const kernel = keep(cv.getStructuringElement(cv.MORPH_RECT, new cv.Size(5, 5)));
    const whitened = keep(new cv.MatVector());
    for (let index = 0; index < channels.size(); index++) {
      const channel = keep(channels.get(index));
      const small = keep(new cv.Mat());
      cv.resize(channel, small, smallSize, 0, 0, cv.INTER_AREA);
      cv.dilate(small, small, kernel);
      cv.medianBlur(small, small, 15);
      const background = keep(new cv.Mat());
      cv.resize(small, background, new cv.Size(channel.cols, channel.rows), 0, 0, cv.INTER_LINEAR);
      const flat = keep(new cv.Mat());
      cv.divide(channel, background, flat, 255);
      // v' = 255 - (255 - v * 255 / white) * gain, saturated to 0..255.
      flat.convertTo(flat, -1, (INK_GAIN * 255) / PAPER_WHITE, 255 * (1 - INK_GAIN));
      whitened.push_back(flat);
    }
    const merged = keep(new cv.Mat());
    cv.merge(whitened, merged);
    const page = new cv.Mat();
    cv.cvtColor(merged, page, cv.COLOR_RGB2RGBA);
    return page;
  });
}

export type QuarterTurns = 0 | 1 | 2 | 3;

/** Rotates clockwise by quarter turns; returns a new Mat in every case so callers own exactly one result. */
export function rotate(cv: OpenCv, rgba: Mat, quarterTurns: QuarterTurns): Mat {
  const turned = new cv.Mat();
  if (quarterTurns === 0) {
    rgba.copyTo(turned);
  } else {
    const codes = [cv.ROTATE_90_CLOCKWISE, cv.ROTATE_180, cv.ROTATE_90_COUNTERCLOCKWISE];
    cv.rotate(rgba, turned, codes[quarterTurns - 1]);
  }
  return turned;
}
