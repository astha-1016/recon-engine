package com.reconcile.app.service;

import com.reconcile.engine.Csv;
import org.apache.poi.ss.usermodel.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Reads an uploaded CSV or Excel (.xlsx/.xls) sheet into header-keyed rows. */
public final class RowReader {
    private RowReader() {}

    public static List<Map<String, String>> read(MultipartFile file) {
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        try {
            if (name.endsWith(".csv")) return Csv.parseRecords(new String(file.getBytes(), StandardCharsets.UTF_8));
            if (name.endsWith(".xlsx") || name.endsWith(".xls")) return readExcel(file.getInputStream());
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read file: " + e.getMessage());
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Upload a .csv, .xlsx or .xls file");
    }

    private static List<Map<String, String>> readExcel(InputStream in) throws IOException {
        List<Map<String, String>> rows = new ArrayList<>();
        try (Workbook wb = WorkbookFactory.create(in)) {
            Sheet sheet = wb.getSheetAt(0);
            DataFormatter fmt = new DataFormatter();
            Row header = sheet.getRow(sheet.getFirstRowNum());
            if (header == null) return rows;
            List<String> cols = new ArrayList<>();
            for (Cell c : header) cols.add(fmt.formatCellValue(c).trim());
            for (int r = sheet.getFirstRowNum() + 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;
                Map<String, String> m = new LinkedHashMap<>();
                boolean any = false;
                for (int c = 0; c < cols.size(); c++) {
                    String v = cellText(row.getCell(c), fmt);
                    if (!v.isEmpty()) any = true;
                    m.put(cols.get(c), v);
                }
                if (any) rows.add(m);
            }
        }
        return rows;
    }

    private static String cellText(Cell cell, DataFormatter fmt) {
        if (cell == null) return "";
        if (cell.getCellType() == CellType.NUMERIC) {
            if (DateUtil.isCellDateFormatted(cell)) return cell.getLocalDateTimeCellValue().toLocalDate().toString();
            return BigDecimal.valueOf(cell.getNumericCellValue()).toPlainString();
        }
        return fmt.formatCellValue(cell).trim();
    }
}
