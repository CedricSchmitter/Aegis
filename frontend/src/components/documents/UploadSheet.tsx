import { useEffect, useState, type FormEvent } from "react";
import { useTranslation } from "react-i18next";
import { uploadDocument } from "../../api/upload";
import type { CategoryDto } from "../../api/types";
import { todayIso } from "../../lib/format";
import { problemMessage } from "../../lib/problem";
import { CONTENT_TYPES, detectFormat, localRejection, uploadName, type UploadFormat } from "../../lib/uploadFormat";
import { MAX_PAGES } from "../../scan/draft";
import type { ScanDraftState } from "../../scan/useScanDraft";
import { ErrorBox, InfoBox } from "../common";
import { FileCapture } from "../FileCapture";
import { FolderSelect } from "../FolderSelect";
import { ScanPages } from "../ScanPages";
import { Sheet } from "../ui";
import { UploadSubjects } from "../UploadSubjects";

interface Props {
  open: boolean;
  onClose: () => void;
  scan: ScanDraftState;
  categories: CategoryDto[];
  desktop: boolean;
  /** A file dropped on the list's drop target; it is taken over as the file to upload. */
  droppedFile: File | null;
  onDroppedFileTaken: () => void;
  /** Called once the server confirmed durable storage, with the sentence that says so. */
  onUploaded: (message: string) => Promise<void>;
}

/**
 * Upload and scan in one sheet: a bottom sheet on a phone, a centred panel on a desktop. The scan draft
 * lives outside the sheet, so closing it keeps the pages; an upload is only reported as saved once the server has
 * stored it durably, and a failed attempt keeps everything for a retry.
 */
export function UploadSheet({ open, onClose, scan, categories, desktop, droppedFile, onDroppedFileTaken, onUploaded }: Props) {
  const { t } = useTranslation();
  const [file, setFile] = useState<File | null>(null);
  const [fileFormat, setFileFormat] = useState<UploadFormat | null>(null);
  const [subjects, setSubjects] = useState<string[]>([]);
  const [folder, setFolder] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [uploading, setUploading] = useState(false);
  // A single new photo (the camera, or one image from the gallery) is shown with its detected sheet right away.
  const [checkId, setCheckId] = useState<string | null>(null);
  const scanning = scan.pages.length > 0;

  const clearFile = () => {
    setFile(null);
    setFileFormat(null);
    setError(null);
  };

  const selectFile = async (chosen: File) => {
    const format = await detectFormat(chosen);
    const rejection = localRejection(chosen, format);
    setFile(chosen);
    setFileFormat(rejection ? null : format);
    setError(rejection ? t(`errors.${rejection}`) : null);
  };

  useEffect(() => {
    if (!droppedFile) return;
    onDroppedFileTaken();
    if (!scanning) void selectFile(droppedFile);
    // selectFile only depends on the translation function; the dropped file is taken over once.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [droppedFile]);

  const addPhotos = (photos: File[]) => {
    clearFile();
    const added = scan.addPhotos(photos);
    setCheckId(added.length === 1 ? added[0] : null);
  };

  /** The scan as one PDF, or the chosen file unchanged; null (with the reason shown) when it cannot be sent. */
  const uploadable = async (): Promise<File | null> => {
    if (scanning) {
      // The PDF is built again for every attempt; the same pages give the same bytes, so a retry after a lost
      // answer is recognized by the server as the document it already stored.
      const pdf = await scan.toPdf();
      const rejection = localRejection(pdf, "pdf");
      if (rejection) setError(t(`errors.${rejection}`));
      return rejection ? null : pdf;
    }
    if (!file || !fileFormat) return null;
    const named = uploadName(file.name, t("share.fallbackName", { date: todayIso() }), fileFormat);
    return named === file.name ? file : new File([file], named, { type: CONTENT_TYPES[fileFormat] });
  };

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setUploading(true);
    setError(null);
    try {
      const upload = await uploadable();
      if (!upload) return;
      const result = await uploadDocument(upload, { subjects, categoryId: folder });
      const message = result.duplicate
        ? t(result.document.trash ? "documents.duplicateTrashed" : "documents.duplicate", { title: result.document.title })
        : t("documents.uploaded", { title: result.document.title });
      clearFile();
      if (scanning) await scan.clear();
      onClose();
      await onUploaded(message);
    } catch (failure) {
      setError(problemMessage(t, failure));
    } finally {
      setUploading(false);
    }
  };

  // The sheet stays while the server has not answered, so a failure is always seen, and while the corner editor is
  // open, whose Escape closes only the editor.
  const close = () => {
    if (uploading || document.querySelector("[data-page-editor]")) return;
    onClose();
  };

  return (
    <Sheet open={open} onClose={close} title={t("shell.upload")} testId="upload-panel" wide>
      <form className="flex flex-col gap-4" onSubmit={submit} data-testid="upload-form">
        {scan.restored > 0 && scanning && <InfoBox>{t("scan.restored", { count: scan.restored })}</InfoBox>}
        <FileCapture
          file={file}
          format={fileFormat}
          pageCount={scan.pages.length}
          disabled={uploading}
          desktop={desktop}
          onPhotos={addPhotos}
          onFile={(chosen) => void selectFile(chosen)}
          onClear={clearFile}
        />
        {scan.problem && <ErrorBox message={t(`scan.problem.${scan.problem}`, { max: MAX_PAGES })} />}
        {scanning && <ScanPages scan={scan} disabled={uploading} checkId={checkId} onChecked={() => setCheckId(null)} />}
        <FolderSelect
          categories={categories}
          value={folder}
          onChange={setFolder}
          label={t("documents.uploadFolder")}
          id="upload-folder"
        />
        <UploadSubjects value={subjects} onChange={setSubjects} />
        <ErrorBox message={error} />
        <button
          type="submit"
          className="btn-primary w-full"
          disabled={uploading || (scanning ? scan.processing : !file || !fileFormat)}
          data-testid="upload-submit"
        >
          {uploading
            ? t("documents.uploading")
            : scanning
              ? t("scan.save", { count: scan.pages.length })
              : t("documents.uploadSubmit")}
        </button>
      </form>
    </Sheet>
  );
}
