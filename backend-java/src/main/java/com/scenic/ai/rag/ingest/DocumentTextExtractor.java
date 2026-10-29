package com.scenic.ai.rag.ingest;

import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 文档正文提取 —— 对应 {@code backend-python/app/services/document_parser.py}。
 *
 * Python 侧只支持 docx / xlsx（没有 PDF，故此处也不引 PDFBox）；本实现额外支持 txt / md，
 * 因为赛题资料包里含纯文本说明文件。
 */
@Component
@Slf4j
public class DocumentTextExtractor {

    /** 支持的文件后缀（小写，不含点） */
    public static final List<String> SUPPORTED_TYPES = List.of("docx", "xlsx", "txt", "md");

    public String extract(Path file, String fileType) throws IOException {
        String type = normalize(fileType);
        String text = switch (type) {
            case "docx" -> extractDocx(file);
            case "xlsx" -> extractXlsx(file);
            case "txt", "md" -> extractPlainText(file);
            default -> throw new IOException("不支持的文件类型: " + fileType + "，仅支持 " + SUPPORTED_TYPES);
        };
        log.info("文档解析完成: {} ({}), {} 字符", file.getFileName(), type, text.length());
        return text;
    }

    private String normalize(String fileType) {
        if (fileType == null) {
            return "";
        }
        return fileType.toLowerCase().strip().replace(".", "");
    }

    /** 段落 + 表格，表格行用 " | " 连接（与 Python 侧一致） */
    String extractDocx(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file);
             XWPFDocument doc = new XWPFDocument(in)) {

            List<String> parts = new ArrayList<>();

            for (XWPFParagraph para : doc.getParagraphs()) {
                String text = para.getText();
                if (text != null && !text.isBlank()) {
                    parts.add(text.strip());
                }
            }

            for (XWPFTable table : doc.getTables()) {
                for (XWPFTableRow row : table.getRows()) {
                    List<String> cells = new ArrayList<>();
                    for (XWPFTableCell cell : row.getTableCells()) {
                        String text = cell.getText();
                        if (text != null && !text.isBlank()) {
                            cells.add(text.strip());
                        }
                    }
                    if (!cells.isEmpty()) {
                        parts.add(String.join(" | ", cells));
                    }
                }
            }

            return String.join("\n\n", parts);
        }
    }

    /** 首行当表头，每行输出为 "表头: 值, 表头: 值"（与 Python 侧一致） */
    String extractXlsx(Path file) throws IOException {
        DataFormatter formatter = new DataFormatter();

        try (InputStream in = Files.newInputStream(file);
             XSSFWorkbook workbook = new XSSFWorkbook(in)) {

            List<String> parts = new ArrayList<>();

            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                Sheet sheet = workbook.getSheetAt(i);
                if (sheet == null) {
                    continue;
                }

                List<String> headers = new ArrayList<>();
                boolean headerLoaded = false;

                for (Row row : sheet) {
                    if (!headerLoaded) {
                        for (int c = 0; c < row.getLastCellNum(); c++) {
                            headers.add(cellText(row.getCell(c), formatter));
                        }
                        headerLoaded = true;
                        parts.add("【工作表: " + sheet.getSheetName() + "】");
                        continue;
                    }

                    List<String> pairs = new ArrayList<>();
                    int idx = 0;
                    for (int c = 0; c < row.getLastCellNum(); c++) {
                        String value = cellText(row.getCell(c), formatter);
                        String header = idx < headers.size() ? headers.get(idx) : "";
                        if (!header.isEmpty() && !value.isEmpty()) {
                            pairs.add(header + ": " + value);
                        }
                        idx++;
                    }
                    if (!pairs.isEmpty()) {
                        parts.add(String.join(", ", pairs));
                    }
                }
            }

            return String.join("\n", parts);
        }
    }

    String extractPlainText(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    /**
     * 单元格取值。
     *
     * <p>本方法的定位是「**取值归一化**」—— 去掉格式化带来的 {@code .0}、首尾空白、
     * 日期序列号、科学计数法。它**不是**完整的文本清洗：中间多余空格、全角/半角混用、
     * 换行符等一概不处理，需要的话得在外面再包一层。
     *
     * <p>2026-10-29（D22）修了两类噪音：
     * <ol>
     *   <li><b>日期变成序列号</b>：Excel 里日期本质就是个数字，
     *       原实现直接走 NUMERIC 分支，{@code 2019-01-01} 会输出成 {@code 43466}。
     *       必须在数值处理**之前**用 {@link DateUtil#isCellDateFormatted} 单独判断。</li>
     *   <li><b>科学计数法</b>：{@code 1.0E20} 这类会原样输出，污染检索。
     *       改用 {@link BigDecimal#toPlainString()} 展开。</li>
     * </ol>
     *
     * <p>仍然救不回来的：超过 15 位的数字 ID —— Excel 自己就把精度截断了，
     * 那是数据源的问题，读取侧无能为力。
     */
    private String cellText(Cell cell, DataFormatter formatter) {
        if (cell == null) {
            return "";
        }
        if (cell.getCellType() == CellType.NUMERIC) {
            // 日期要先判：这一支必须排在纯数值之前，否则日期会变成 43466 这类序列号
            if (DateUtil.isCellDateFormatted(cell)) {
                LocalDateTime dateTime = cell.getLocalDateTimeCellValue();
                if (dateTime == null) {
                    return formatter.formatCellValue(cell).strip();
                }
                return dateTime.toLocalTime().equals(LocalTime.MIDNIGHT)
                        ? dateTime.toLocalDate().toString()                       // 2019-01-01
                        : dateTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
            }
            double value = cell.getNumericCellValue();
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                return formatter.formatCellValue(cell).strip();
            }
            // BigDecimal 而不是 String.valueOf：后者会把 1e20 写成 "1.0E20"；
            // toPlainString 展开成完整数字，stripTrailingZeros 去掉 "2019.0" 的尾巴
            return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
        }
        if (cell.getCellType() == CellType.STRING) {
            return cell.getStringCellValue().strip();
        }
        if (cell.getCellType() == CellType.BOOLEAN) {
            return String.valueOf(cell.getBooleanCellValue());
        }
        return formatter.formatCellValue(cell).strip();
    }
}
