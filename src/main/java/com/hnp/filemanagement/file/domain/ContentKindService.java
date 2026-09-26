package com.hnp.filemanagement.file.domain;

import com.hnp.filemanagement.audit.domain.ActionHistoryService;
import com.hnp.filemanagement.audit.domain.ActionEnum;
import com.hnp.filemanagement.audit.domain.EntityEnum;
import com.hnp.filemanagement.shared.exception.DuplicateResourceException;
import com.hnp.filemanagement.shared.exception.InvalidDataException;
import com.hnp.filemanagement.shared.exception.ResourceNotFoundException;
import com.hnp.filemanagement.file.persistence.ContentKindRepository;
import com.hnp.filemanagement.file.persistence.UploadPolicyRepository;
import com.hnp.filemanagement.identity.persistence.UserRepository;
import jakarta.annotation.PostConstruct;
import org.apache.tika.Tika;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The custom half of the content catalogue: kinds an administrator adds at run time, and the
 * probe that describes a sample file so that a kind can be defined from it.
 *
 * <p><b>The registry is what the table says.</b> Every custom row is pushed into
 * {@link ContentTypes#registerCustom} at start-up and after each add or delete; the built-in kinds
 * never leave the code. A custom kind therefore behaves exactly like a built-in one on every
 * upload route - same extension check, same byte check - except that it is never rendered
 * inline.
 *
 * <p><b>The probe stores nothing.</b> It reads the first block of the sample, names the
 * extension, asks Tika what the bytes look like, shows them as hex, and says whether the
 * catalogue already knows the extension and whether the bytes would pass. It is advice for the
 * person filling in the form; the rule that gets stored is what they submit.
 *
 * <p><b>What cannot be added.</b> A built-in extension (the code's rule wins and cannot be
 * loosened from a form), and anything a browser would execute as a document of this origin
 * ({@link ContentTypes#isBrowserActive}). A signature must be at least two bytes, or the kind
 * must be text-only: a rule that every file satisfies would make the byte check meaningless.
 */
@Service
public class ContentKindService {

    private static final Logger logger = LoggerFactory.getLogger(ContentKindService.class);

    private static final Pattern EXTENSION = Pattern.compile("[a-z0-9]{1,16}");
    private static final Pattern MEDIA_TYPE = Pattern.compile("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+", Pattern.CASE_INSENSITIVE);
    private static final int SUGGESTED_SIGNATURE_BYTES = 4;
    private static final int SHOWN_HEAD_BYTES = 32;

    private final ContentKindRepository contentKindRepository;
    private final UploadPolicyRepository uploadPolicyRepository;
    private final UserRepository userRepository;
    private final ActionHistoryService actionHistoryService;
    private final Tika tika = new Tika();

    public ContentKindService(ContentKindRepository contentKindRepository, UploadPolicyRepository uploadPolicyRepository,
                              UserRepository userRepository, ActionHistoryService actionHistoryService) {
        this.contentKindRepository = contentKindRepository;
        this.uploadPolicyRepository = uploadPolicyRepository;
        this.userRepository = userRepository;
        this.actionHistoryService = actionHistoryService;
    }

    /** Loads the table into the registry once at start-up, so the first upload sees every kind. */
    @PostConstruct
    public void refreshRegistry() {
        List<ContentKind> rows = contentKindRepository.findAllByOrderByIdAsc();
        ContentTypes.registerCustom(rows.stream().map(ContentKindService::toCustom).toList());
        logger.info("content kinds: {} built-in, {} custom", ContentTypes.kinds().stream().filter(ContentTypes.Kind::builtIn).count(), rows.size());
    }

    // ---------------------------------------------------------------- reading

    /** The custom rows, in the order they were added. */
    @Transactional(readOnly = true)
    public List<ContentKind> customKinds() {
        return contentKindRepository.findAllByOrderByIdAsc();
    }

    // ---------------------------------------------------------------- the probe

    /** Describes a sample without storing a byte of it. */
    public ContentProbeDTO probe(MultipartFile sample) {
        String name = sample.getOriginalFilename();
        String extension = ContentTypes.extensionOf(name);
        byte[] head = ContentTypes.headOf(sample);

        String detected;
        try {
            detected = tika.detect(head, name);
        } catch (RuntimeException e) {
            logger.warn("tika could not detect a type for {}: {}", name, e.getMessage());
            detected = "application/octet-stream";
        }

        HexFormat spaced = HexFormat.ofDelimiter(" ").withUpperCase();
        String headHex = spaced.formatHex(head, 0, Math.min(head.length, SHOWN_HEAD_BYTES));
        String suggested = HexFormat.of().withUpperCase().formatHex(head, 0, Math.min(head.length, SUGGESTED_SIGNATURE_BYTES));

        Optional<ContentTypes.Kind> known = ContentTypes.kindOf(extension);
        String refused = null;
        if (extension.isEmpty()) {
            refused = "the file has no extension";
        } else if (ContentTypes.isBrowserActive(extension)) {
            refused = "a browser would run ." + extension + " as a document; it can never be a kind";
        }

        return new ContentProbeDTO(name, extension, sample.getContentType(), detected, headHex, suggested,
                ContentTypes.looksLikeText(head), sample.getSize(),
                known.map(ContentTypes.Kind::mediaType).orElse(null),
                known.map(kind -> kind.matches().test(head)).orElse(false),
                refused);
    }

    // ---------------------------------------------------------------- editing

    @Transactional
    public ContentKind add(ContentKindForm form, int principalId) {
        String extension = form.getExtension() == null ? "" : form.getExtension().trim().toLowerCase(Locale.ROOT).replaceFirst("^\\.", "");
        if (!EXTENSION.matcher(extension).matches()) {
            throw new InvalidDataException("extension must be 1-16 letters or digits: " + form.getExtension());
        }
        if (ContentTypes.isBuiltIn(extension)) {
            throw new InvalidDataException("." + extension + " is a built-in kind and cannot be redefined");
        }
        if (ContentTypes.isBrowserActive(extension)) {
            throw new InvalidDataException("." + extension + " is something a browser would run; it can never be a kind");
        }
        if (contentKindRepository.existsByExtension(extension)) {
            throw new DuplicateResourceException("a kind for ." + extension + " already exists");
        }
        String mediaType = form.getMediaType() == null ? "" : form.getMediaType().trim().toLowerCase(Locale.ROOT);
        if (!MEDIA_TYPE.matcher(mediaType).matches()) {
            throw new InvalidDataException("media type must look like type/subtype: " + form.getMediaType());
        }

        ContentKind kind = new ContentKind();
        kind.setExtension(extension);
        kind.setMediaType(mediaType);
        kind.setTextOnly(form.isTextOnly());
        kind.setDescription(form.getDescription() == null || form.getDescription().isBlank() ? null : form.getDescription().trim());
        if (form.isTextOnly()) {
            kind.setSignatureHex(null);
            kind.setSignatureOffset(0);
        } else {
            String hex = form.getSignatureHex() == null ? "" : form.getSignatureHex().replaceAll("[\\s:]", "").toUpperCase(Locale.ROOT);
            if (hex.length() < 4 || hex.length() % 2 != 0 || !hex.matches("[0-9A-F]+")) {
                throw new InvalidDataException("signature must be at least two bytes of hex, or the kind must be text-only");
            }
            if (hex.length() > 64) {
                throw new InvalidDataException("signature may be at most 32 bytes");
            }
            int offset = form.getSignatureOffset() == null ? 0 : form.getSignatureOffset();
            if (offset < 0 || offset > 1024) {
                throw new InvalidDataException("signature offset must be between 0 and 1024");
            }
            kind.setSignatureHex(hex);
            kind.setSignatureOffset(offset);
        }
        kind.setCreatedBy(userRepository.getReferenceById(principalId));
        kind = contentKindRepository.save(kind);

        actionHistoryService.saveActionHistory(EntityEnum.ContentKind, kind.getId(), ActionEnum.CREATE,
                principalId, "CREATE CONTENT_KIND", "CREATE CONTENT_KIND ." + extension + " as " + mediaType);
        refreshAfterCommit();
        return kind;
    }

    /** Removes the kind and every upload-policy rule that named it. Files already stored keep their rows and are served as octet-stream. */
    @Transactional
    public void delete(String extension, int principalId) {
        ContentKind kind = contentKindRepository.findByExtension(extension.toLowerCase(Locale.ROOT)).orElseThrow(
                () -> new ResourceNotFoundException("no custom kind for ." + extension));
        int rules = uploadPolicyRepository.deleteRulesForExtension(kind.getExtension());
        contentKindRepository.delete(kind);
        actionHistoryService.saveActionHistory(EntityEnum.ContentKind, kind.getId(), ActionEnum.DELETE,
                principalId, "DELETE CONTENT_KIND", "DELETE CONTENT_KIND ." + kind.getExtension() + ", policy rules removed=" + rules);
        refreshAfterCommit();
    }

    // ---------------------------------------------------------------- pieces

    /**
     * The registry follows the table. It is refreshed at once, so the request that made the
     * change sees it, and again when the transaction completes - which, after a rollback, is
     * what takes a kind back out that no row describes any more.
     */
    private void refreshAfterCommit() {
        refreshRegistry();
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    refreshRegistry();
                }
            });
        }
    }

    private static ContentTypes.CustomKind toCustom(ContentKind row) {
        byte[] signature = row.isTextOnly() || row.getSignatureHex() == null ? new byte[0] : HexFormat.of().parseHex(row.getSignatureHex());
        return new ContentTypes.CustomKind(row.getExtension(), row.getMediaType(), signature, row.getSignatureOffset(), row.isTextOnly());
    }
}
