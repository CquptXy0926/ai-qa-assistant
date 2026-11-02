package com.itheima.qa.rag;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.sl.usermodel.SlideShow;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 多格式文档解析器：输出带位置标注（页码/行号/Slide 号）的文本段。
 * 支持：txt/md/pdf/docx/xlsx/pptx/html。扫描版 PDF 提取不到文字时抛异常提示。
 */
public final class DocumentParser {

    /** 一个位置段：location 用于引用溯源，如 "第3页" / "行1-45" / "Slide 2" / "Sheet1" */
    public record Segment(String location, String text) {}

    private DocumentParser() {}

    public static List<Segment> parse(String fileName, byte[] data) throws IOException {
        String name = fileName.toLowerCase();
        if (name.endsWith(".pdf")) {
            return parsePdf(data);
        } else if (name.endsWith(".docx")) {
            return parseDocx(data);
        } else if (name.endsWith(".xlsx")) {
            return parseXlsx(data);
        } else if (name.endsWith(".pptx")) {
            return parsePptx(data);
        } else if (name.endsWith(".html") || name.endsWith(".htm")) {
            return segmentsFromLines(stripHtml(new String(data, StandardCharsets.UTF_8)));
        } else if (name.endsWith(".txt") || name.endsWith(".md") || name.endsWith(".csv")) {
            return segmentsFromLines(new String(data, StandardCharsets.UTF_8));
        }
        throw new IOException("不支持的文件格式：" + fileName);
    }

    /** PDF：逐页提取，页码作为位置 */
    private static List<Segment> parsePdf(byte[] data) throws IOException {
        List<Segment> segments = new ArrayList<>();
        StringBuilder empty = new StringBuilder();
        try (PDDocument doc = Loader.loadPDF(data)) {
            PDFTextStripper stripper = new PDFTextStripper();
            int pages = doc.getNumberOfPages();
            for (int p = 1; p <= pages; p++) {
                stripper.setStartPage(p);
                stripper.setEndPage(p);
                String text = stripper.getText(doc).trim();
                if (text.isEmpty()) {
                    empty.append("第").append(p).append("页 ");
                } else {
                    segments.add(new Segment("第" + p + "页", text));
                }
            }
        }
        if (segments.isEmpty()) {
            throw new IOException("未能从 PDF 提取到文字（可能是扫描版），需 OCR 处理或转为文字版后重新上传。空白页：" + empty);
        }
        return segments;
    }

    /** docx：整体提取 */
    private static List<Segment> parseDocx(byte[] data) throws IOException {
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(data));
             XWPFWordExtractor extractor = new XWPFWordExtractor(doc)) {
            String text = extractor.getText().trim();
            if (text.isEmpty()) {
                throw new IOException("docx 文档内容为空");
            }
            return segmentsFromLines(text);
        }
    }

    /** xlsx：按 Sheet 提取，逐行拼接 */
    private static List<Segment> parseXlsx(byte[] data) throws IOException {
        List<Segment> segments = new ArrayList<>();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(data))) {
            for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                XSSFSheet sheet = wb.getSheetAt(i);
                StringBuilder sb = new StringBuilder();
                for (org.apache.poi.ss.usermodel.Row row : sheet) {
                    row.forEach(cell -> sb.append(cell.toString()).append('\t'));
                    if (!sb.isEmpty() && sb.charAt(sb.length() - 1) == '\t') {
                        sb.setLength(sb.length() - 1);
                    }
                    sb.append('\n');
                }
                String text = sb.toString().trim();
                if (!text.isEmpty()) {
                    segments.add(new Segment("Sheet:" + sheet.getSheetName(), text));
                }
            }
        }
        if (segments.isEmpty()) {
            throw new IOException("xlsx 文档内容为空");
        }
        return segments;
    }

    /** pptx：按 Slide 提取文本形状 */
    private static List<Segment> parsePptx(byte[] data) throws IOException {
        List<Segment> segments = new ArrayList<>();
        try (SlideShow<?, ?> show = new XMLSlideShow(new ByteArrayInputStream(data))) {
            int idx = 0;
            for (XSLFSlide slide : ((XMLSlideShow) show).getSlides()) {
                idx++;
                StringBuilder sb = new StringBuilder();
                for (XSLFShape shape : slide.getShapes()) {
                    if (shape instanceof XSLFTextShape ts) {
                        String t = ts.getText().trim();
                        if (!t.isEmpty()) {
                            sb.append(t).append('\n');
                        }
                    }
                }
                String text = sb.toString().trim();
                if (!text.isEmpty()) {
                    segments.add(new Segment("Slide " + idx, text));
                }
            }
        }
        if (segments.isEmpty()) {
            throw new IOException("pptx 文档无可提取文本");
        }
        return segments;
    }

    /** 按行累积到 MAX_SEGMENT 长度，产出 "行a-b" 位置段 */
    private static List<Segment> segmentsFromLines(String text) {
        final int maxSegment = 4000;
        List<Segment> segments = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        int start = 1;
        int len = 0;
        StringBuilder buf = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            buf.append(lines[i]).append('\n');
            len += lines[i].length();
            if (len >= maxSegment || i == lines.length - 1) {
                segments.add(new Segment("行" + start + "-" + (i + 1), buf.toString().trim()));
                buf.setLength(0);
                start = i + 2;
                len = 0;
            }
        }
        segments.removeIf(s -> s.text().isEmpty());
        if (segments.isEmpty()) {
            throw new IllegalArgumentException("文档内容为空");
        }
        return segments;
    }

    private static String stripHtml(String html) {
        return html.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ")
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</(p|div|h[1-6]|li|tr)>", "\n")
                .replaceAll("<[^>]+>", " ")
                .replaceAll("[ \\t]+", " ")
                .replaceAll("\n{2,}", "\n")
                .trim();
    }
}
