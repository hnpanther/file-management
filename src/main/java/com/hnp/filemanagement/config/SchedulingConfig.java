package com.hnp.filemanagement.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on the one scheduled job this application has: {@code StorageSweeper}, which settles byte
 * writes whose request never got to say how it ended (roadmap 2.3).
 *
 * <p>It is a configuration of its own rather than an annotation on the application class so that
 * the reason for a scheduler thread existing at all is written down next to it. The job checks
 * {@code filemanagement.storage.sweep-enabled} itself instead of being conditional on it, so that
 * the setting can be read in one place and the bean stays there to be called by hand.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
