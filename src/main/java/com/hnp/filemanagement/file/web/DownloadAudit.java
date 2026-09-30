package com.hnp.filemanagement.file.web;

import com.hnp.filemanagement.file.domain.DownloadChannel;
import com.hnp.filemanagement.file.domain.DownloadEvent;
import com.hnp.filemanagement.file.domain.DownloadRecorder;
import com.hnp.filemanagement.file.domain.FileDownloadDTO;
import com.hnp.filemanagement.identity.security.ActingApiKey;
import com.hnp.filemanagement.identity.security.UserDetailsImpl;
import com.hnp.filemanagement.shared.web.GlobalGeneralLogging;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;

/**
 * The one call every download handler makes once it has decided to send the bytes (2.7.0): it
 * turns the request in progress into a {@link DownloadEvent} - the person or the API key, the
 * address, the channel - and hands it to {@link DownloadRecorder}, which never makes it wait.
 *
 * <p>What is a download and what is not, decided here once for every handler: a {@code HEAD}
 * answers headers and is not; a {@code Range} that starts past the first byte is the rest of a
 * download already counted - a PDF viewer's second and later requests - and is not; everything
 * else is. Nothing here can fail a download: whatever goes wrong is logged and the bytes go out.
 */
@Component
public class DownloadAudit {

    private static final Logger logger = LoggerFactory.getLogger(DownloadAudit.class);

    private final DownloadRecorder downloadRecorder;
    private final Clock clock;

    public DownloadAudit(DownloadRecorder downloadRecorder, Clock clock) {
        this.downloadRecorder = downloadRecorder;
        this.clock = clock;
    }

    /** Records that this revision is being sent, by whoever is asking, through this channel. */
    public void served(FileDownloadDTO download, DownloadChannel channel) {
        try {
            HttpServletRequest request = currentRequest();
            if (request == null || download == null || download.getFileInfoId() == null
                    || "HEAD".equalsIgnoreCase(request.getMethod())
                    || !startsAtFirstByte(request.getHeader(HttpHeaders.RANGE))) {
                return;
            }
            UserDetailsImpl principal = GlobalGeneralLogging.currentPrincipal();
            downloadRecorder.record(new DownloadEvent(Instant.now(clock), channel,
                    download.getFileInfoId(), download.getFileDetailsId(), download.getFileName(),
                    download.getVersion(), download.getFolderId(),
                    principal == null ? null : principal.getId(),
                    principal == null ? null : principal.getUsername(),
                    ActingApiKey.currentId(), download.getShareLinkId(), request.getRemoteAddr()));
        } catch (RuntimeException e) {
            logger.error("could not record a download of fileDetails id="
                    + (download == null ? null : download.getFileDetailsId()), e);
        }
    }

    /**
     * Whether a response to this {@code Range} starts at the first byte - no range at all, or a
     * first range from 0. One this cannot read is taken as a whole download: Spring answers such a
     * header with the whole file.
     */
    static boolean startsAtFirstByte(String range) {
        if (range == null || range.isBlank()) {
            return true;
        }
        String value = range.trim().toLowerCase(Locale.ROOT);
        if (!value.startsWith("bytes=")) {
            return true;
        }
        String first = value.substring("bytes=".length()).split(",")[0].trim();
        if (first.startsWith("-")) {
            // A suffix range, "the last n bytes": never the start of a download.
            return false;
        }
        int dash = first.indexOf('-');
        String start = dash < 0 ? first : first.substring(0, dash).trim();
        try {
            return Long.parseLong(start) == 0;
        } catch (NumberFormatException e) {
            return true;
        }
    }

    private static HttpServletRequest currentRequest() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        return attributes instanceof ServletRequestAttributes servlet ? servlet.getRequest() : null;
    }
}
