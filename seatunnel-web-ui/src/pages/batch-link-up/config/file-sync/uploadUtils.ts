export interface PickedFile {
  file: File;
  relativePath: string;
}

// Keep multipart requests below Spring's 200 MB default, including request overhead.
export const MAX_UPLOAD_BATCH_BYTES = 180 * 1024 * 1024;
export const MAX_UPLOAD_BATCH_FILES = 1000;

export const splitUploadBatches = <T extends PickedFile>(pickedFiles: T[]): T[][] => {
  const batches: T[][] = [];
  let currentBatch: T[] = [];
  let currentBytes = 0;

  for (const pickedFile of pickedFiles) {
    const fileSize = Number.isFinite(pickedFile.file.size) ? pickedFile.file.size : 0;
    const exceedsBatchLimit =
      currentBatch.length > 0 &&
      (currentBatch.length >= MAX_UPLOAD_BATCH_FILES ||
        currentBytes + fileSize > MAX_UPLOAD_BATCH_BYTES);

    if (exceedsBatchLimit) {
      batches.push(currentBatch);
      currentBatch = [];
      currentBytes = 0;
    }

    currentBatch.push(pickedFile);
    currentBytes += fileSize;
  }

  if (currentBatch.length > 0) batches.push(currentBatch);
  return batches;
};
