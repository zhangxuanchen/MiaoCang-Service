package com.miaocang.service;

import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * 内容解析服务：把三种来源统一抽取为 (标题, 摘要, 正文文本)。
 * - 网页链接：Jsoup 抓取 title / meta description / body 文本
 * - Word(.docx/.doc)：POI 抽取段落与表格文本
 * - Excel(.xlsx/.xls)：POI 抽取 sheet 名、表头与单元格文本
 */
@Service
public class ExtractService {

    private static final Logger log = LoggerFactory.getLogger(ExtractService.class);
    private static final String UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36";

    @Value("${miaocang.gravity.max-text-length:20000}")
    private int maxTextLength;

    @Value("${miaocang.files.dir:./data/files}")
    private String fileDir;

    public record ExtractResult(String title, String summary, String rawText, boolean ok, String error) {}

    /** 解析网页链接 */
    public ExtractResult extractUrl(String url) {
        try {
            Document doc = Jsoup.connect(url)
                    .userAgent(UA)
                    .timeout(15000)
                    .followRedirects(true)
                    .get();
            String title = doc.title() == null || doc.title().isBlank() ? hostOf(url) : doc.title().trim();
            String desc = null;
            Elements meta = doc.select("meta[name=description], meta[property=og:description]");
            if (!meta.isEmpty()) {
                desc = meta.first().attr("content");
            }
            String bodyText = doc.body() != null ? doc.body().text() : "";
            String text = truncate(bodyText);
            String summary = desc != null && !desc.isBlank() ? truncate(desc.trim(), 300) : truncate(text, 200);
            return new ExtractResult(title, summary, text, true, null);
        } catch (Exception e) {
            log.warn("网页抓取失败: {} - {}", url, e.getMessage());
            return new ExtractResult(hostOf(url), "（网页抓取失败，仅保存链接，可稍后重试或手动补充标题）",
                    "", false, e.getMessage());
        }
    }

    /** 解析上传的 Word/Excel 文件：保存文件 + 抽取文本 */
    public record StoredFile(Path path, String originalName, String contentType) {}

    public StoredFile store(MultipartFileLike file) throws Exception {
        String original = file.originalName();
        String ext = extOf(original);
        Path dir = Paths.get(fileDir).toAbsolutePath();
        Files.createDirectories(dir);
        String stored = System.currentTimeMillis() + "_" + java.util.UUID.randomUUID().toString().substring(0, 8) + ext;
        Path target = dir.resolve(stored);
        try (InputStream in = file.inputStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return new StoredFile(target, original, file.contentType());
    }

    public ExtractResult extractFile(Path path, String originalName) {
        String lower = originalName.toLowerCase(Locale.ROOT);
        try {
            if (lower.endsWith(".docx")) {
                return extractDocx(path, originalName);
            }
            if (lower.endsWith(".doc")) {
                return extractDoc(path, originalName);
            }
            if (lower.endsWith(".xlsx") || lower.endsWith(".xls")) {
                return extractExcel(path, originalName);
            }
            return new ExtractResult(baseName(originalName), "（暂不支持的文件格式，已保存原文件）", "", false, "unsupported");
        } catch (Exception e) {
            log.warn("文件解析失败: {} - {}", originalName, e.getMessage());
            return new ExtractResult(baseName(originalName), "（文件解析失败，已保存原文件）", "", false, e.getMessage());
        }
    }

    private ExtractResult extractDocx(Path path, String name) throws Exception {
        try (InputStream in = Files.newInputStream(path); XWPFDocument doc = new XWPFDocument(in);
             XWPFWordExtractor extractor = new XWPFWordExtractor(doc)) {
            String text = truncate(extractor.getText());
            return new ExtractResult(baseName(name), truncate(text, 300), text, true, null);
        }
    }

    private ExtractResult extractDoc(Path path, String name) throws Exception {
        try (InputStream in = Files.newInputStream(path); HWPFDocument doc = new HWPFDocument(in);
             WordExtractor extractor = new WordExtractor(doc)) {
            String text = truncate(extractor.getText());
            return new ExtractResult(baseName(name), truncate(text, 300), text, true, null);
        }
    }

    private ExtractResult extractExcel(Path path, String name) throws Exception {
        try (InputStream in = Files.newInputStream(path); Workbook wb = WorkbookFactory.create(in)) {
            StringBuilder sb = new StringBuilder();
            DataFormatter fmt = new DataFormatter();
            for (int s = 0; s < wb.getNumberOfSheets() && s < 20; s++) {
                Sheet sheet = wb.getSheetAt(s);
                sb.append("【工作表: ").append(sheet.getSheetName()).append("】\n");
                int maxRow = Math.min(sheet.getLastRowNum() + 1, 2000);
                for (int r = 0; r < maxRow; r++) {
                    Row row = sheet.getRow(r);
                    if (row == null) continue;
                    StringBuilder line = new StringBuilder();
                    int maxCol = Math.min(row.getLastCellNum(), 60);
                    for (int c = 0; c < maxCol; c++) {
                        Cell cell = row.getCell(c);
                        if (cell == null) continue;
                        String v = fmt.formatCellValue(cell).trim();
                        if (!v.isEmpty()) line.append(v).append(' ');
                    }
                    if (!line.isEmpty()) sb.append(line.toString().trim()).append('\n');
                    if (sb.length() > maxTextLength) break;
                }
            }
            String text = truncate(sb.toString());
            return new ExtractResult(baseName(name), truncate(text, 300), text, true, null);
        }
    }

    // ---------- utils ----------

    private String truncate(String s) {
        return truncate(s, maxTextLength);
    }

    private String truncate(String s, int len) {
        if (s == null) return "";
        return s.length() <= len ? s : s.substring(0, len);
    }

    public String hostOf(String url) {
        try {
            return URI.create(url).getHost() == null ? url : URI.create(url).getHost();
        } catch (Exception e) {
            return url;
        }
    }

    private String extOf(String name) {
        int i = name.lastIndexOf('.');
        return i >= 0 ? name.substring(i) : "";
    }

    private String baseName(String name) {
        String n = name;
        int i = n.lastIndexOf('.');
        if (i > 0) n = n.substring(0, i);
        return n.isBlank() ? name : n;
    }

    /** 简化的上传文件抽象，便于测试与解耦 Multipart */
    public interface MultipartFileLike {
        String originalName();
        String contentType();
        InputStream inputStream() throws Exception;
    }
}
