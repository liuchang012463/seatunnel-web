package org.apache.seatunnel.web.api.log;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Reads bounded UTF-8 pages from a growing per-instance log file. */
public final class JobLogFileReader {

    public static final int MAX_PAGE_BYTES = 256 * 1024;
    public static final int MAX_PAGE_LINES = 2_000;

    public record Page(String content, long startCursor, long endCursor, boolean hasOlder, boolean hasNewer) {
    }

    public Page read(Path path, Long afterCursor, Long beforeCursor) throws IOException {
        if (afterCursor != null && beforeCursor != null) {
            throw new IllegalArgumentException("afterCursor and beforeCursor cannot both be set");
        }
        if (path == null || !Files.isRegularFile(path)) {
            return new Page("", 0L, 0L, false, false);
        }

        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "r")) {
            long fileLength = file.length();
            if (fileLength == 0L) {
                return new Page("", 0L, 0L, false, false);
            }

            long end;
            long start;
            if (afterCursor != null) {
                start = clamp(afterCursor, fileLength);
                end = boundedEnd(file, start, fileLength);
            } else if (beforeCursor != null) {
                end = clamp(beforeCursor, fileLength);
                start = boundedStart(file, end);
            } else {
                end = fileLength;
                start = tailStart(file, fileLength);
            }

            start = alignUtf8Start(file, start, end);
            end = alignUtf8End(file, start, end);

            if (end < start) {
                end = start;
            }
            String content = readRange(file, start, end);
            return new Page(content, start, end, start > 0L, end < fileLength);
        }
    }

    private long tailStart(RandomAccessFile file, long fileLength) throws IOException {
        long lowerBound = Math.max(0L, fileLength - MAX_PAGE_BYTES);
        long start = lowerBound;
        int lines = 0;
        for (long position = fileLength - 1; position >= lowerBound; position--) {
            if (byteAt(file, position) == '\n' && ++lines >= MAX_PAGE_LINES) {
                start = position + 1;
                break;
            }
        }
        return alignStart(file, start, fileLength, lowerBound);
    }

    private long boundedStart(RandomAccessFile file, long end) throws IOException {
        long lowerBound = Math.max(0L, end - MAX_PAGE_BYTES);
        long start = lowerBound;
        int lines = 0;
        for (long position = end - 1; position >= lowerBound; position--) {
            if (byteAt(file, position) == '\n' && ++lines >= MAX_PAGE_LINES) {
                start = position + 1;
                break;
            }
        }
        return alignStart(file, start, end, lowerBound);
    }

    private long alignStart(RandomAccessFile file, long start, long end, long lowerBound) throws IOException {
        if (start <= 0L || start >= end || byteAt(file, start - 1) == '\n') {
            return start;
        }

        long original = start;
        file.seek(start);
        while (file.getFilePointer() < end) {
            if (file.read() == '\n') {
                return file.getFilePointer();
            }
        }
        // A single line can be larger than the page budget. Keep its bounded tail
        // instead of returning an empty page after dropping the only line.
        return lowerBound > 0L ? alignUtf8Start(file, lowerBound, end) : original;
    }

    private long boundedEnd(RandomAccessFile file, long start, long fileLength) throws IOException {
        long end = Math.min(fileLength, start + MAX_PAGE_BYTES);
        int lines = 0;
        file.seek(start);
        while (file.getFilePointer() < end) {
            if (file.read() == '\n' && ++lines >= MAX_PAGE_LINES) {
                return file.getFilePointer();
            }
        }
        return end;
    }

    private long alignUtf8Start(RandomAccessFile file, long start, long end) throws IOException {
        while (start < end && isUtf8Continuation(byteAt(file, start))) {
            start++;
        }
        return start;
    }

    private long alignUtf8End(RandomAccessFile file, long start, long end) throws IOException {
        if (end <= start) {
            return end;
        }

        long sequenceStart = end - 1L;
        while (sequenceStart > start && isUtf8Continuation(byteAt(file, sequenceStart))) {
            sequenceStart--;
        }
        int firstByte = Byte.toUnsignedInt(byteAt(file, sequenceStart));
        int expectedBytes = utf8Length(firstByte);
        long actualBytes = end - sequenceStart;
        return expectedBytes > actualBytes ? sequenceStart : end;
    }

    private boolean isUtf8Continuation(byte value) {
        return (value & 0xC0) == 0x80;
    }

    private int utf8Length(int firstByte) {
        if ((firstByte & 0x80) == 0) {
            return 1;
        }
        if ((firstByte & 0xE0) == 0xC0) {
            return 2;
        }
        if ((firstByte & 0xF0) == 0xE0) {
            return 3;
        }
        if ((firstByte & 0xF8) == 0xF0) {
            return 4;
        }
        return 1;
    }

    private String readRange(RandomAccessFile file, long start, long end) throws IOException {
        int length = Math.toIntExact(Math.min(MAX_PAGE_BYTES, Math.max(0L, end - start)));
        byte[] bytes = new byte[length];
        file.seek(start);
        file.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private byte byteAt(RandomAccessFile file, long position) throws IOException {
        file.seek(position);
        return file.readByte();
    }

    private long clamp(Long cursor, long fileLength) {
        if (cursor == null || cursor <= 0L) {
            return 0L;
        }
        return Math.min(cursor, fileLength);
    }
}
