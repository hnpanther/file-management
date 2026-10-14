package com.hnp.filemanagement.report.web;

import com.hnp.filemanagement.identity.security.UserDetailsImpl;
import com.hnp.filemanagement.report.Report;
import com.hnp.filemanagement.report.ReportFormat;
import com.hnp.filemanagement.report.ReportService;
import com.hnp.filemanagement.report.ReportView;
import com.hnp.filemanagement.shared.exception.ResourceNotFoundException;
import com.hnp.filemanagement.shared.web.GlobalGeneralLogging;
import com.hnp.filemanagement.shared.web.UiMessages;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/**
 * The reports on the files (roadmap 12.9): a page each at {@code /reports/{slug}}, the overview at
 * {@code /reports}, and each table as a CSV a spreadsheet opens (UTF-8 with a byte-order mark, so that
 * Excel reads Persian). Every figure is over the folders the reader may read. Nothing is changed here,
 * so there is no action history; the log says which report, and its folder.
 */
@Controller
@RequestMapping("/reports")
public class ReportController {

    private static final String VIEW = "reports/reports.html";

    private final ReportService reportService;
    private final ReportFormat format;
    private final UiMessages messages;
    private final GlobalGeneralLogging globalGeneralLogging;
    private final Clock clock;

    public ReportController(ReportService reportService, ReportFormat format, UiMessages messages,
                            GlobalGeneralLogging globalGeneralLogging, Clock clock) {
        this.reportService = reportService;
        this.format = format;
        this.messages = messages;
        this.globalGeneralLogging = globalGeneralLogging;
        this.clock = clock;
    }

    // REPORTS_PAGE
    @PreAuthorize("hasAuthority('REPORTS_PAGE') || hasAuthority('ADMIN')")
    @GetMapping
    public String overview(@AuthenticationPrincipal UserDetailsImpl userDetails, Model model) {
        return page(userDetails, Report.OVERVIEW.slug(), null, null, null, model);
    }

    // REPORTS_PAGE
    @PreAuthorize("hasAuthority('REPORTS_PAGE') || hasAuthority('ADMIN')")
    @GetMapping("{slug}")
    public String page(@AuthenticationPrincipal UserDetailsImpl userDetails, @PathVariable("slug") String slug,
                       @RequestParam(value = "folder", required = false) Integer folder,
                       @RequestParam(value = "months", required = false) Integer months,
                       @RequestParam(value = "days", required = false) Integer days,
                       Model model) {
        ReportService.Request request = requestOf(slug, folder, months, days);
        globalGeneralLogging.detail("report=" + request.report().slug() + (request.folder() == null ? "" : " folder=" + request.folder()));
        model.addAttribute("view", reportService.view(request, userDetails.getId()));
        model.addAttribute("request", request);
        model.addAttribute("reports", Report.values());
        model.addAttribute("monthChoices", ReportService.MONTHS);
        model.addAttribute("dayChoices", ReportService.COLD_DAYS);
        model.addAttribute("trail", reportService.trailOf(request.folder(), userDetails.getId()));
        return VIEW;
    }

    // REPORTS_PAGE
    @PreAuthorize("hasAuthority('REPORTS_PAGE') || hasAuthority('ADMIN')")
    @GetMapping("{slug}/csv")
    public ResponseEntity<byte[]> csv(@AuthenticationPrincipal UserDetailsImpl userDetails, @PathVariable("slug") String slug,
                                      @RequestParam(value = "table") String table,
                                      @RequestParam(value = "folder", required = false) Integer folder,
                                      @RequestParam(value = "months", required = false) Integer months,
                                      @RequestParam(value = "days", required = false) Integer days) {
        ReportService.Request request = requestOf(slug, folder, months, days);
        ReportView view = reportService.view(request, userDetails.getId());
        ReportView.Table found = view.table(table).orElseThrow(() -> new ResourceNotFoundException("no table " + table + " in report " + slug));
        globalGeneralLogging.detail("report=" + request.report().slug() + " csv=" + found.id()
                + (request.folder() == null ? "" : " folder=" + request.folder()) + " rows=" + found.rows().size());
        String name = "report-" + request.report().slug() + "-" + found.id() + "-" + LocalDate.now(clock) + ".csv";
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(csvOf(found));
    }

    private static ReportService.Request requestOf(String slug, Integer folder, Integer months, Integer days) {
        Report report = Report.of(slug).orElseThrow(() -> new ResourceNotFoundException("no report " + slug));
        return new ReportService.Request(report, folder, months, days);
    }

    /** The table as CSV: a byte-order mark, a header of its columns' names, every cell quoted. */
    byte[] csvOf(ReportView.Table table) {
        StringBuilder csv = new StringBuilder("﻿");
        List<ReportView.Column> columns = table.columns();
        for (int i = 0; i < columns.size(); i++) {
            csv.append(i == 0 ? "" : ",").append(quoted(messages.get(columns.get(i).code()), false));
        }
        csv.append("\r\n");
        for (ReportView.Row row : table.rows()) {
            for (int i = 0; i < columns.size(); i++) {
                ReportView.Kind kind = columns.get(i).kind();
                boolean text = kind == ReportView.Kind.TEXT || kind == ReportView.Kind.LABEL || kind == ReportView.Kind.FOLDER
                        || kind == ReportView.Kind.FILE;
                csv.append(i == 0 ? "" : ",").append(quoted(format.written(kind, row.cells().get(i)), text));
            }
            csv.append("\r\n");
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * A cell in double quotes, its quotes doubled. A name a person wrote that begins as a formula would
     * ({@code = + - @}, a tab or a return) is preceded by an apostrophe, so that a spreadsheet shows it
     * and never runs it (CSV injection).
     */
    static String quoted(String value, boolean written) {
        String cell = value == null ? "" : value;
        if (written && !cell.isEmpty() && "=+-@\t\r".indexOf(cell.charAt(0)) >= 0) {
            cell = "'" + cell;
        }
        return "\"" + cell.replace("\"", "\"\"") + "\"";
    }
}
