package com.hnp.filemanagement.copy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

import java.util.Arrays;

/**
 * {@code java -jar file-management.jar --spring.profiles.active=copy}: copies the MySQL database
 * the service is configured for into the PostgreSQL named by
 * {@code FILEMANAGEMENT_COPY_TARGET_URL} (roadmap 3.5), prints what it did, and exits - {@code 0}
 * when every table verified, {@code 1} when the copy did not verify and was rolled back, {@code 2}
 * when it refused to start.
 *
 * <p><b>It is not the application.</b> Started as the application, the copy would run everything a
 * start runs, against the production MySQL: {@code DataInitializer}'s reconciliation, the checksum
 * backfill, the storage sweeper, Flyway. So this reads the configuration exactly the way the
 * service does - the same {@code application.properties}, the same environment variables, the
 * same external file - through a Spring context with nothing in it: no auto-configuration, no
 * component scan, no web server. {@link DatabaseCopy} does the rest with two plain connections.
 *
 * <p>Only the command-line argument selects it, never an environment variable: a service
 * definition that happened to carry {@code SPRING_PROFILES_ACTIVE=copy} must not turn the next
 * restart of the service into a copy.
 */
public final class DatabaseCopyCommand {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseCopyCommand.class);

    public static final int VERIFIED = 0;
    public static final int NOT_VERIFIED = 1;
    public static final int REFUSED = 2;

    private DatabaseCopyCommand() {
    }

    /** Whether the command line asks for the copy: {@code --spring.profiles.active=copy}. */
    public static boolean isRequested(String[] args) {
        return Arrays.stream(args)
                .filter(arg -> arg.startsWith("--spring.profiles.active="))
                .map(arg -> arg.substring("--spring.profiles.active=".length()))
                .flatMap(profiles -> Arrays.stream(profiles.split(",")))
                .anyMatch(profile -> profile.trim().equals("copy"));
    }

    /** Runs the copy and answers the process's exit status. */
    public static int run(String[] args) {
        SpringApplication configurationOnly = new SpringApplication(ConfigurationOnly.class);
        configurationOnly.setWebApplicationType(WebApplicationType.NONE);
        configurationOnly.setBannerMode(Banner.Mode.OFF);
        configurationOnly.setRegisterShutdownHook(false);
        // Otherwise the first log line reads "Starting FileManagementApplication", which is the
        // one thing an operator must not believe on the night of the cut-over.
        configurationOnly.setMainApplicationClass(DatabaseCopyCommand.class);

        try (ConfigurableApplicationContext context = configurationOnly.run(args)) {
            Environment environment = context.getEnvironment();
            CopyEndpoint source = endpoint(environment, "spring.datasource.url",
                    "spring.datasource.username", "spring.datasource.password", "the MySQL source");
            CopyEndpoint target = endpoint(environment, "filemanagement.copy.target-url",
                    "filemanagement.copy.target-username", "filemanagement.copy.target-password",
                    "the PostgreSQL target (FILEMANAGEMENT_COPY_TARGET_URL)");
            if (!source.url().startsWith("jdbc:mysql:")) {
                throw new DatabaseCopy.Refused("the source must be the MySQL database; spring.datasource.url is "
                        + source.describe());
            }
            if (!target.url().startsWith("jdbc:postgresql:")) {
                throw new DatabaseCopy.Refused("the target must be a PostgreSQL database; it is " + target.describe());
            }

            DatabaseCopy.Report report = new DatabaseCopy().copy(source, target);
            report.tables().forEach(table -> logger.info("  {} {}: MySQL {} row(s), PostgreSQL {} row(s)",
                    table.identical() ? "ok      " : "MISMATCH", table.table(), table.sourceRows(), table.targetRows()));
            if (report.verified()) {
                logger.info("copy VERIFIED: every table identical on both sides; the identities are past the copied ids");
                return VERIFIED;
            }
            logger.error("copy NOT VERIFIED, rolled back - do not cut over: {}", report.problems());
            return NOT_VERIFIED;
        } catch (DatabaseCopy.Refused e) {
            logger.error("copy refused, nothing was written: {}", e.getMessage());
            return REFUSED;
        } catch (RuntimeException e) {
            logger.error("copy failed, nothing was committed", e);
            return REFUSED;
        }
    }

    private static CopyEndpoint endpoint(Environment environment, String url, String username, String password,
                                         String what) {
        String value = environment.getProperty(url);
        if (value == null || value.isBlank()) {
            throw new DatabaseCopy.Refused("no URL for " + what + " (" + url + ")");
        }
        return new CopyEndpoint(value, environment.getProperty(username), environment.getProperty(password));
    }

    /**
     * The whole of the copy's Spring context: nothing. Not annotated, so the application's component
     * scan never finds it and Spring Boot configures nothing around it - it is only what
     * {@link SpringApplication} needs to read the configuration the way the service does.
     */
    static final class ConfigurationOnly {
    }
}
