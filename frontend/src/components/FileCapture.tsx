import { useEffect, useRef, useState, type ChangeEvent, type DragEvent } from "react";
import { useTranslation } from "react-i18next";
import { formatBytes } from "../lib/format";
import { DOCUMENT_ACCEPT, PHOTO_ACCEPT, isImage, type UploadFormat } from "../lib/uploadFormat";
import { Icon } from "./shell/icons";

interface Props {
  /** A file chosen to be uploaded unchanged; null when there is none. */
  file: File | null;
  /** Format recognized from the content of `file`; null while unknown or when it is not accepted. */
  format: UploadFormat | null;
  /** Pages of the scan in progress; the camera and gallery then add further pages. */
  pageCount: number;
  disabled?: boolean;
  /** On a desktop: a drop zone for a file instead of the camera, which a desktop browser does not open. */
  desktop?: boolean;
  /** Photos from the camera or the gallery, each becoming a scanned page. */
  onPhotos: (photos: File[]) => void;
  /** A file from the file chooser or dropped on the drop zone, uploaded unchanged. */
  onFile: (file: File) => void;
  onClear: () => void;
}

/** Whether a drag carries files, as opposed to text or a link from the page. */
function carriesFiles(event: DragEvent): boolean {
  return [...event.dataTransfer.types].includes("Files");
}

/**
 * The ways to capture a document. The camera (opens directly on Android) and the gallery add pages to a
 * scan, which becomes one PDF; the file chooser and the desktop drop zone upload a PDF or photo unchanged. The
 * options say which one scans and which one keeps the file. While a scan is in progress the file choice
 * is hidden, so a chosen file can never silently replace scanned pages.
 */
export function FileCapture({ file, format, pageCount, disabled, desktop = false, onPhotos, onFile, onClear }: Props) {
  const { t, i18n } = useTranslation();
  const camera = useRef<HTMLInputElement>(null);
  const gallery = useRef<HTMLInputElement>(null);
  const chooser = useRef<HTMLInputElement>(null);
  const [over, setOver] = useState(false);
  const scanning = pageCount > 0;

  const pick = (handle: (files: File[]) => void) => (event: ChangeEvent<HTMLInputElement>) => {
    const chosen = [...(event.target.files ?? [])];
    // Reset so that choosing the same file again (for example after removing it) is noticed.
    event.target.value = "";
    if (chosen.length > 0) handle(chosen);
  };

  const drop = (event: DragEvent) => {
    event.preventDefault();
    setOver(false);
    const dropped = event.dataTransfer.files[0];
    if (dropped && !disabled) onFile(dropped);
  };

  return (
    <div className="flex flex-col gap-2">
      {scanning ? (
        <div className="grid gap-2 sm:grid-cols-2">
          {!desktop && (
            <button type="button" className="btn-secondary" disabled={disabled} onClick={() => camera.current?.click()} data-testid="upload-take-photo">
              <Icon name="camera" className="h-[18px] w-[18px]" />
              {t("scan.addPage")}
            </button>
          )}
          <button type="button" className="btn-secondary" disabled={disabled} onClick={() => gallery.current?.click()} data-testid="upload-choose-image">
            <Icon name="image" className="h-[18px] w-[18px]" />
            {t("scan.addImages")}
          </button>
        </div>
      ) : (
        <>
          {desktop && (
            <div
              className="ui-drop"
              data-over={over}
              data-testid="upload-drop"
              onDragOver={(event) => {
                if (!carriesFiles(event)) return;
                event.preventDefault();
                setOver(true);
              }}
              onDragLeave={() => setOver(false)}
              onDrop={drop}
            >
              <Icon name="upload" className="h-[30px] w-[30px]" />
              <b className="text-[17px] tracking-[0.06em] uppercase">{t("documents.capture.dropTitle")}</b>
              <span className="text-[13px] text-text-3">{t("documents.capture.fileHint")}</span>
              <button type="button" className="btn-secondary" disabled={disabled} onClick={() => chooser.current?.click()} data-testid="upload-choose-file">
                {t("documents.capture.chooseFile")}
              </button>
            </div>
          )}
          {!desktop && (
            <button type="button" className="ui-option" disabled={disabled} onClick={() => camera.current?.click()} data-testid="upload-take-photo">
              <Icon name="camera" className="h-6 w-6" />
              <b>{t("documents.takePhoto")}</b>
              <span>{t("documents.capture.photoHint")}</span>
            </button>
          )}
          <button type="button" className="ui-option" disabled={disabled} onClick={() => gallery.current?.click()} data-testid="upload-choose-image">
            <Icon name="image" className="h-6 w-6" />
            <b>{t(desktop ? "documents.capture.photosAsScan" : "documents.chooseImage")}</b>
            <span>{t("documents.capture.galleryHint")}</span>
          </button>
          {!desktop && (
            <button type="button" className="ui-option" disabled={disabled} onClick={() => chooser.current?.click()} data-testid="upload-choose-file">
              <Icon name="file" className="h-6 w-6" />
              <b>{t("documents.chooseFile")}</b>
              <span>{t("documents.capture.fileHint")}</span>
            </button>
          )}
        </>
      )}
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

      {file && (
        <div className="card flex items-start gap-3 p-3" data-testid="upload-selected">
          {isImage(format) && <PhotoPreview file={file} />}
          <div className="flex min-w-0 flex-1 flex-col gap-1">
            <span className="text-sm font-medium break-words">{file.name}</span>
            <span className="font-mono text-xs text-text-3">{formatBytes(file.size, t, i18n.language)}</span>
            {isImage(format) && <span className="text-xs text-text-3">{t("scan.unchanged")}</span>}
            <div>
              <button type="button" className="btn-ghost -ml-4" disabled={disabled} onClick={onClear}>
                {t("documents.removeFile")}
              </button>
            </div>
          </div>
        </div>
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
    return <p className="w-24 shrink-0 text-xs text-text-3">{t("documents.noPreview")}</p>;
  }
  return url ? (
    <img
      src={url}
      alt={t("documents.photoPreview")}
      className="h-24 w-24 shrink-0 object-cover"
      onError={() => setFailed(true)}
      data-testid="upload-preview"
    />
  ) : null;
}
