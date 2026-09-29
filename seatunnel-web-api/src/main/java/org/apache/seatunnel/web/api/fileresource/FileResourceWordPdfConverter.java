package org.apache.seatunnel.web.api.fileresource;

import org.apache.fontbox.ttf.TrueTypeCollection;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Converts Word documents into a printable PDF for browser preview.
 *
 * <p>DOCX and legacy DOC are extracted through Apache POI, then rendered with
 * PDFBox using a system CJK font when available so Chinese text stays readable.</p>
 */
@Component
public class FileResourceWordPdfConverter {

    private static final float MARGIN = 48f;
    private static final float FONT_SIZE = 12f;
    private static final float LEADING = 18f;

    public boolean supports(String fileName) {
        String extension = extensionOf(fileName);
        return "doc".equals(extension) || "docx".equals(extension);
    }

    public void convert(String fileName, byte[] bytes, OutputStream output) throws IOException {
        String extension = extensionOf(fileName);
        if (!"doc".equals(extension) && !"docx".equals(extension)) {
            throw new IllegalArgumentException("仅支持将 doc/docx 转换为 PDF 预览");
        }
        String text = "docx".equals(extension) ? extractDocx(bytes) : extractDoc(bytes);
        writePdf(text, output);
    }

    private String extractDocx(byte[] bytes) throws IOException {
        try (InputStream input = new ByteArrayInputStream(bytes);
             XWPFDocument document = new XWPFDocument(input);
             XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            return normalizeText(extractor.getText());
        }
    }

    private String extractDoc(byte[] bytes) throws IOException {
        try (InputStream input = new ByteArrayInputStream(bytes);
             HWPFDocument document = new HWPFDocument(input);
             WordExtractor extractor = new WordExtractor(document)) {
            return normalizeText(extractor.getText());
        }
    }

    private void writePdf(String text, OutputStream output) throws IOException {
        List<Closeable> closables = new ArrayList<>();
        try (PDDocument document = new PDDocument()) {
            PDType0Font font = loadPreviewFont(document, closables);
            PDRectangle mediaBox = PDRectangle.A4;
            float width = mediaBox.getWidth() - (MARGIN * 2);
            List<String> lines = wrapLines(text, font, FONT_SIZE, width);
            float yStart = mediaBox.getHeight() - MARGIN;
            float y = yStart;
            PDPage page = new PDPage(mediaBox);
            document.addPage(page);
            PDPageContentStream content = new PDPageContentStream(document, page);
            content.beginText();
            content.setFont(font, FONT_SIZE);
            content.newLineAtOffset(MARGIN, y);

            for (String line : lines) {
                if (y - LEADING < MARGIN) {
                    content.endText();
                    content.close();
                    page = new PDPage(mediaBox);
                    document.addPage(page);
                    content = new PDPageContentStream(document, page);
                    content.beginText();
                    content.setFont(font, FONT_SIZE);
                    y = yStart;
                    content.newLineAtOffset(MARGIN, y);
                }
                String safeLine = sanitizeForFont(line, font);
                content.showText(safeLine.isEmpty() ? " " : safeLine);
                content.newLineAtOffset(0, -LEADING);
                y -= LEADING;
            }

            content.endText();
            content.close();
            document.save(output);
        } finally {
            for (int index = closables.size() - 1; index >= 0; index--) {
                try {
                    closables.get(index).close();
                } catch (IOException ignored) {
                    // Best-effort cleanup after the PDF has already been written.
                }
            }
        }
    }

    private PDType0Font loadPreviewFont(PDDocument document, List<Closeable> closables) throws IOException {
        String windir = System.getenv("WINDIR");
        String[] candidates = {
                windir == null ? null : windir + "\\Fonts\\simhei.ttf",
                windir == null ? null : windir + "\\Fonts\\simkai.ttf",
                windir == null ? null : windir + "\\Fonts\\msyh.ttc",
                windir == null ? null : windir + "\\Fonts\\simsun.ttc",
                "/usr/share/fonts/truetype/wqy/wqy-microhei.ttc",
                "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc",
                "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
                "/System/Library/Fonts/PingFang.ttc",
        };
        for (String path : candidates) {
            if (!StringUtils.hasText(path)) {
                continue;
            }
            File file = new File(path);
            if (!file.isFile()) {
                continue;
            }
            if (path.toLowerCase(Locale.ROOT).endsWith(".ttc")) {
                TrueTypeCollection collection = new TrueTypeCollection(file);
                closables.add(collection);
                AtomicReference<TrueTypeFont> chosen = new AtomicReference<>();
                collection.processAllFonts(font -> {
                    if (chosen.get() == null) {
                        chosen.set(font);
                    }
                });
                TrueTypeFont ttf = chosen.get();
                if (ttf != null) {
                    return PDType0Font.load(document, ttf, true);
                }
            } else {
                return PDType0Font.load(document, file);
            }
        }
        throw new IOException("未找到可用于 Word 预览的中文字体，请在服务器安装微软雅黑/Noto CJK/文泉驿字体");
    }

    private String sanitizeForFont(String line, PDType0Font font) throws IOException {
        if (line == null || line.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder(line.length());
        for (int index = 0; index < line.length(); ) {
            int codePoint = line.codePointAt(index);
            String character = new String(Character.toChars(codePoint));
            try {
                font.encode(character);
                builder.append(character);
            } catch (IllegalArgumentException | UnsupportedOperationException ignored) {
                builder.append('?');
            }
            index += Character.charCount(codePoint);
        }
        return builder.toString();
    }

    private List<String> wrapLines(String text, PDType0Font font, float fontSize, float maxWidth)
            throws IOException {
        List<String> lines = new ArrayList<>();
        String normalized = text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n');
        if (!StringUtils.hasText(normalized)) {
            lines.add("(空文档)");
            return lines;
        }
        for (String paragraph : normalized.split("\n", -1)) {
            if (paragraph.isEmpty()) {
                lines.add("");
                continue;
            }
            StringBuilder current = new StringBuilder();
            for (int index = 0; index < paragraph.length(); ) {
                int codePoint = paragraph.codePointAt(index);
                String character = new String(Character.toChars(codePoint));
                String candidate = current + character;
                float candidateWidth;
                try {
                    candidateWidth = font.getStringWidth(sanitizeForFont(candidate, font)) / 1000 * fontSize;
                } catch (IllegalArgumentException ex) {
                    candidateWidth = maxWidth + 1;
                }
                if (candidateWidth <= maxWidth || current.length() == 0) {
                    current.append(character);
                } else {
                    lines.add(current.toString());
                    current = new StringBuilder(character);
                }
                index += Character.charCount(codePoint);
            }
            lines.add(current.toString());
        }
        return lines;
    }

    private String normalizeText(String text) {
        return text == null ? "" : text.replace("\u0000", "").trim();
    }

    private String extensionOf(String fileName) {
        if (!StringUtils.hasText(fileName)) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
