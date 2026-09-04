import {
  MAX_UPLOAD_BATCH_BYTES,
  MAX_UPLOAD_BATCH_FILES,
  splitUploadBatches,
  type PickedFile,
} from './uploadUtils';

const pickedFile = (size: number, index: number): PickedFile => ({
  file: { size } as File,
  relativePath: `file-${index}`,
});

describe('splitUploadBatches', () => {
  it('keeps every multipart request below the byte limit', () => {
    const files = [
      pickedFile(MAX_UPLOAD_BATCH_BYTES - 1, 1),
      pickedFile(2, 2),
    ];

    const batches = splitUploadBatches(files);

    expect(batches).toHaveLength(2);
    expect(batches.flat()).toEqual(files);
  });

  it('keeps the number of files in a request bounded', () => {
    const files = Array.from({ length: MAX_UPLOAD_BATCH_FILES + 1 }, (_, index) =>
      pickedFile(1, index),
    );

    const batches = splitUploadBatches(files);

    expect(batches).toHaveLength(2);
    expect(batches[0]).toHaveLength(MAX_UPLOAD_BATCH_FILES);
    expect(batches.flat()).toEqual(files);
  });
});
