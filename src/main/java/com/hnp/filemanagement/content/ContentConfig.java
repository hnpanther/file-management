package com.hnp.filemanagement.content;

import com.hnp.filemanagement.storage.BlobStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;

/**
 * The content-search beans (roadmap 11). The worker always exists - the status page asks it why it
 * does or does not run - but is given a Tika client only when the settings let reading run; otherwise
 * nothing that could call Tika is ever made. Only an engine that is not {@code postgres} stops the
 * start: every other setting at worst leaves reading off, said so.
 */
@Configuration
public class ContentConfig {

    @Bean
    public ContentWorker contentWorker(ContentSearchProperties properties, FileContentRepository repository,
                                       BlobStore blobStore, TransactionTemplate transactions, Clock clock) {
        if (!"postgres".equalsIgnoreCase(properties.engine())) {
            throw new IllegalStateException("filemanagement.content-search.engine=" + properties.engine()
                    + ": only 'postgres' is an engine in this release (roadmap 11.6 adds OpenSearch)");
        }
        String why = properties.whyReadingCannotRun().orElse(null);
        ContentReader reader = null;
        if (why == null) {
            ContentSearchProperties.Tika tika = properties.tika();
            TikaClient client = new HttpTikaClient(tika.textUri().orElseThrow(), tika.ocrUri().orElse(null),
                    Duration.ofSeconds(tika.connectTimeoutSeconds()), Duration.ofMinutes(tika.textTimeoutMinutes()),
                    Duration.ofMinutes(tika.ocrTimeoutMinutes()));
            reader = new ContentReader(client, properties.extraction());
        }
        return new ContentWorker(repository, reader, blobStore, transactions, clock, properties, why);
    }
}
