package com.hnp.filemanagement.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Name;
import org.springframework.validation.annotation.Validated;

/**
 * Every setting this application owns, in one tree ({@code docs/issues.md} issue 27, roadmap 2.1).
 *
 * <p>Before this, each setting was an {@code @Value} string repeated wherever it was read - with
 * its default written again at every site, so two sites could disagree about what "unset" means,
 * and nothing listed what the application could be configured with. Here the names are declared
 * once, the defaults are Java values, the types are types (a {@code Duration} is a duration, a
 * size is a {@code DataSize}), and a value out of range fails the start rather than the hundredth
 * request: {@code @Validated} with the constraints below.
 *
 * <p>Spring's own settings ({@code server.port}, {@code spring.datasource.*},
 * {@code spring.servlet.multipart.*}) stay where they are - they are not ours to rename. The one
 * that is read here, {@code spring.servlet.multipart.max-file-size}, is a ceiling the upload
 * policy must not exceed, so it is read as what it is: Spring's.
 *
 * @param baseDir      the storage root; every stored byte is under it. Must exist and be writable
 * @param defaults     page sizes for the list views. Bound from {@code filemanagement.default.*},
 *                     the name it has always had - renaming a published setting silently changes
 *                     behaviour on every installation that sets it
 * @param folderAccess whether a person's folder grants are enforced as well as their permissions
 * @param folders      the shape of the folder tree
 * @param profiles     personal folders
 * @param storage      how unfinished byte writes are cleaned up
 * @param shareLinks   temporary share links
 * @param bootstrap    what the first start creates
 * @param auth         Active Directory, off by default
 */
@Validated
@ConfigurationProperties(prefix = "filemanagement")
public record FileManagementProperties(
        @NotNull String baseDir,
        @Name("default") @Valid Defaults defaults,
        @Valid FolderAccess folderAccess,
        @Valid Folders folders,
        @Valid Profiles profiles,
        @Valid Storage storage,
        @Valid ShareLinks shareLinks,
        @Valid Bootstrap bootstrap,
        @Valid Auth auth) {

    public FileManagementProperties {
        defaults = defaults == null ? new Defaults(null, null) : defaults;
        folderAccess = folderAccess == null ? new FolderAccess(null) : folderAccess;
        folders = folders == null ? new Folders(null, null) : folders;
        profiles = profiles == null ? new Profiles(null) : profiles;
        storage = storage == null ? new Storage(null, null, null, null) : storage;
        shareLinks = shareLinks == null ? new ShareLinks(null, null, null, null, null) : shareLinks;
        bootstrap = bootstrap == null ? new Bootstrap(null) : bootstrap;
        auth = auth == null ? new Auth(null) : auth;
    }

    /** The tree with nothing set but the storage root: every other value its documented default. */
    public static FileManagementProperties defaults(String baseDir) {
        return new FileManagementProperties(baseDir, null, null, null, null, null, null, null, null);
    }

    /** The same tree with different directory settings - what a test varies. */
    public FileManagementProperties withActiveDirectory(ActiveDirectory activedirectory) {
        return new FileManagementProperties(baseDir, defaults, folderAccess, folders, profiles,
                storage, shareLinks, bootstrap, new Auth(new Ldap(activedirectory)));
    }

    /** Rows per page in a list view, and items per dropdown. */
    public record Defaults(@Min(1) Integer pageSize, @Min(1) Integer elementSize) {
        public Defaults {
            pageSize = pageSize == null ? 50 : pageSize;
            elementSize = elementSize == null ? 50 : elementSize;
        }
    }

    /**
     * @param enabled false leaves a person's folder grants unenforced - the state every
     *                installation starts in, so that grants can be created before they bite.
     *                API keys are scoped whatever this says
     */
    public record FolderAccess(Boolean enabled) {
        public FolderAccess {
            enabled = enabled != null && enabled;
        }
    }

    /**
     * @param maxDepth        how deep the tree may go below {@code Home}; a limit for people
     * @param maxDeleteFiles  the most files one recursive delete may remove
     */
    public record Folders(@Min(1) Integer maxDepth, @Min(1) Integer maxDeleteFiles) {
        public Folders {
            maxDepth = maxDepth == null ? 6 : maxDepth;
            maxDeleteFiles = maxDeleteFiles == null ? 1000 : maxDeleteFiles;
        }
    }

    /** @param defaultQuotaMb the quota a new personal folder starts with; 0 for none */
    public record Profiles(@Min(0) Long defaultQuotaMb) {
        public Profiles {
            defaultQuotaMb = defaultQuotaMb == null ? 0L : defaultQuotaMb;
        }

        /** The default quota in bytes, or null for none. */
        public Long defaultQuotaBytes() {
            return defaultQuotaMb <= 0 ? null : defaultQuotaMb * 1024L * 1024L;
        }
    }

    /**
     * The sweeper that settles byte writes nobody finished (roadmap 2.3, {@code StorageSweeper}).
     *
     * @param sweepEnabled          false stops the scheduled run; the sweep can still be called
     * @param sweepEveryMinutes     how often it runs. Read here for the record's sake - the
     *                              schedule itself reads the property, because an annotation is
     *                              resolved before any binding happens, so the two defaults must
     *                              stay the same number
     * @param unfinishedAfterMinutes how old a write must be before it is considered abandoned.
     *                              Longer than any request could possibly take: a write still in
     *                              flight must never be swept
     * @param sweepBatchSize        notes settled per read
     */
    public record Storage(Boolean sweepEnabled, @Min(1) Integer sweepEveryMinutes,
                          @Min(1) Integer unfinishedAfterMinutes, @Min(1) Integer sweepBatchSize) {
        public Storage {
            sweepEnabled = sweepEnabled == null || sweepEnabled;
            sweepEveryMinutes = sweepEveryMinutes == null ? 15 : sweepEveryMinutes;
            unfinishedAfterMinutes = unfinishedAfterMinutes == null ? 60 : unfinishedAfterMinutes;
            sweepBatchSize = sweepBatchSize == null ? 200 : sweepBatchSize;
        }
    }

    /**
     * @param maxMinutes        the longest a link may live; a longer request is clamped to it
     * @param defaultMinutes    its validity when the maker does not say
     * @param password          whether a link may, or must, carry a password
     * @param maxFailedAttempts wrong passwords before the link locks
     * @param lockMinutes       how long it stays locked
     */
    public record ShareLinks(@Min(1) Long maxMinutes, @Min(1) Long defaultMinutes, PasswordPolicy password,
                             @Min(1) Integer maxFailedAttempts, @Min(1) Long lockMinutes) {

        /** Whether a share link may, or must, carry a password. */
        public enum PasswordPolicy { OPTIONAL, REQUIRED }

        public ShareLinks {
            maxMinutes = maxMinutes == null ? 1440L : maxMinutes;
            defaultMinutes = defaultMinutes == null ? 60L : defaultMinutes;
            password = password == null ? PasswordPolicy.OPTIONAL : password;
            maxFailedAttempts = maxFailedAttempts == null ? 5 : maxFailedAttempts;
            lockMinutes = lockMinutes == null ? 15L : lockMinutes;
        }

        public boolean passwordRequired() {
            return password == PasswordPolicy.REQUIRED;
        }
    }

    /**
     * @param adminPassword the first administrator's password, on the first start of an empty
     *                      database. Deliberately without a default: an installation that does not
     *                      set it gets no administrator and a loud line in the log, rather than one
     *                      with a password from the git history
     */
    public record Bootstrap(String adminPassword) {
        public Bootstrap {
            adminPassword = adminPassword == null ? "" : adminPassword;
        }
    }

    /** @param ldap the directory settings; {@code enabled} false means the provider stands aside */
    public record Auth(@Valid Ldap ldap) {
        public Auth {
            ldap = ldap == null ? new Ldap(null) : ldap;
        }
    }

    public record Ldap(@Valid ActiveDirectory activedirectory) {
        public Ldap {
            activedirectory = activedirectory == null
                    ? new ActiveDirectory(null, null, null, null, null, null, null, null, null, null)
                    : activedirectory;
        }
    }

    /**
     * @param enabled           whether the directory provider takes part in authentication at all
     * @param verifyCertificate false trusts every certificate the directory presents - the whole
     *                          point of {@code ldaps://} given away, and logged as such
     */
    public record ActiveDirectory(Boolean enabled, String domain, String url,
                                  @Min(1) Integer connectTimeoutMs, @Min(1) Integer readTimeoutMs,
                                  String truststore, String truststorePassword, String truststoreType,
                                  Boolean verifyHostname, Boolean verifyCertificate) {
        public ActiveDirectory {
            enabled = enabled != null && enabled;
            domain = domain == null ? "" : domain;
            url = url == null ? "" : url;
            connectTimeoutMs = connectTimeoutMs == null ? 5000 : connectTimeoutMs;
            readTimeoutMs = readTimeoutMs == null ? 10000 : readTimeoutMs;
            truststore = truststore == null ? "" : truststore;
            truststorePassword = truststorePassword == null ? "" : truststorePassword;
            truststoreType = truststoreType == null || truststoreType.isBlank() ? "PKCS12" : truststoreType;
            verifyHostname = verifyHostname == null || verifyHostname;
            verifyCertificate = verifyCertificate == null || verifyCertificate;
        }
    }
}
