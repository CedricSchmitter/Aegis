import { useEffect, useRef, useState, type ChangeEvent } from "react";
import { useTranslation } from "react-i18next";
import { formatBytes } from "../lib/format";
import { DOCUMENT_ACCEPT, PHOTO_ACCEPT, isImage, type UploadFormat } from "../lib/uploadFormat";

interface Props {
  /** A file chosen to be uploaded unchanged; null when there is none. */
  file: File | null;
  /** Format recognized from the content of `file`; null while unknown or when it is not accepted. */
  format: UploadFormat | null;
  /** Pages of the scan in progress; the camera and gallery then add further pages. */
  pageCount: number;
  disabled?: boolean;
  /** Photos from the camera or the gallery, each becoming a scanned page. */
  onPhotos: (photos: File[]) => void;
  /** A file from the file chooser, uploaded unchanged. */
  onFile: (file: File) => void;
  onClear: () => void;
}

/**
 * The three ways to capture a document on a phone. The camera (opens directly on Android) and the photo picker
 * add pages to a scan, which becomes one PDF; the file chooser uploads a PDF or photo unchanged. While a scan is in
 * progress the file chooser is hidden, so a chosen file can never silently replace scanned pages.
 */
export function FileCapture({ file, format, pageCount, disabled, onPhotos, onFile, onClear }: Props) {
  const { t, i18n } = useTranslation();
  const camera = useRef<HTMLInputElement>(null);
  const gallery = useRef<HTMLInputElement>(null);
  const chooser = useRef<HTMLInputElement>(null);
  const scanning = pageCount > 0;

  const pick = (handle: (files: File[]) => void) => (event: ChangeEvent<HTMLInputElement>) => {
    const chosen = [...(event.target.files ?? [])];
    // Reset so that choosing the same file again (for example after removing it) is noticed.
    event.target.value = "";
    if (chosen.length > 0) handle(chosen);
  };

  return (
    <div className="flex flex-col gap-3">
      <div className="grid gap-2 sm:grid-cols-3">
        <button
          type="button"
          className="btn-primary"
          disabled={disabled}
          onClick={() => camera.current?.click()}
          data-testid="upload-take-photo"
        >
          {t(scanning ? "scan.addPage" : "documents.takePhoto")}
        </button>
        <button
          type="button"
          className="btn-secondary"
          disabled={disabled}
          onClick={() => gallery.current?.click()}
          data-testid="upload-choose-image"
        >
          {t(scanning ? "scan.addImages" : "documents.chooseImage")}
        </button>
        {!scanning && (
          <button
            type="button"
            className="btn-secondary"
            disabled={disabled}
            onClick={() => chooser.current?.click()}
            data-testid="upload-choose-file"
          >
            {t("documents.chooseFile")}
          </button>
        )}
      </div>
      <input
        ref={camera}
        type="file"
        accept="image/*"
        capture="environment"
        hidden
        onChange={pick(onPhotos)}
        data-testid="upload-camera-input"
      />
      <input
        ref={gallery}
        type="file"
        accept={PHOTO_ACCEPT}
        multiple
        hidden
        onChange={pick(onPhotos)}
        data-testid="upload-image-input"
      />
      <input
        ref={chooser}
        type="file"
        accept={DOCUMENT_ACCEPT}
        hidden
        onChange={pick((files) => onFile(files[0]))}
        data-testid="upload-input"
      />

      {file ? (
        <div className="flex items-start gap-3 rounded-lg border border-stone-200 p-2 dark:border-stone-700" data-testid="upload-selected">
          {isImage(format) && <PhotoPreview file={file} />}
          <div className="flex min-w-0 flex-1 flex-col gap-1">
            <span className="break-words text-sm font-medium">{file.name}</span>
            <span className="text-xs text-stone-600 dark:text-stone-400">{formatBytes(file.size, t, i18n.language)}</span>
            {isImage(format) && <span className="text-xs text-stone-600 dark:text-stone-400">{t("scan.unchanged")}</span>}
            <div>
              <button type="button" className="btn-ghost -ml-4" disabled={disabled} onClick={onClear}>
                {t("documents.removeFile")}
              </button>
            </div>
          </div>
        </div>
      ) : (
        !scanning && <p className="text-sm text-stone-500">{t("documents.noFile")}</p>
      )}
    </div>
  );
}

/** Thumbnail of a chosen photo. Formats the browser cannot decode (HEIC in most browsers) get a short note. */
function PhotoPreview({ file }: { file: File }) {
  const { t } = useTranslation();
  const [url, setUrl] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    const objectUrl = URL.createObjectURL(file);
    setUrl(objectUrl);
    setFailed(false);
    return () => URL.revokeObjectURL(objectUrl);
  }, [file]);

  if (failed) {
    return <p className="w-24 shrink-0 text-xs text-stone-500">{t("documents.noPreview")}</p>;
  }
  return url ? (
    <img
      src={url}
      alt={t("documents.photoPreview")}
      className="h-24 w-24 shrink-0 rounded-md object-cover"
      onError={() => setFailed(true)}
      data-testid="upload-preview"
    />
  ) : null;
}
