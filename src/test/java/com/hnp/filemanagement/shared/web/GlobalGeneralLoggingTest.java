package com.hnp.filemanagement.shared.web;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.hnp.filemanagement.identity.domain.UserDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;

import static org.assertj.core.api.Assertions.assertThat;

/** What a log line may say: a share link's token never (roadmap 10.5), and a password never. */
class GlobalGeneralLoggingTest {

    /**
     * A form that did not bind is logged by field and constraint. The binding result's own
     * {@code toString} - what was logged before - quotes every rejected value, and on the user
     * forms one of them is the password.
     */
    @Test
    @DisplayName("a failed form is logged by field and constraint, never with the values that were sent")
    void aFailedFormNamesFieldsNotValues() {
        UserDTO form = new UserDTO();
        form.setPassword("hunter2-secret");
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(form, "userDTO");
        bindingResult.addError(new FieldError("userDTO", "password", "hunter2-secret", false,
                new String[]{"Length"}, null, "too short"));
        bindingResult.addError(new FieldError("userDTO", "username", null, false,
                new String[]{"NotEmpty"}, null, "must not be empty"));

        Logger logger = (Logger) LoggerFactory.getLogger(GlobalGeneralLogging.class);
        Level level = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
        try {
            new GlobalGeneralLogging().invalid(bindingResult);
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(level);
        }

        assertThat(appender.list).singleElement().extracting(ILoggingEvent::getFormattedMessage)
                .asString()
                .contains("ValidationError: password Length, username NotEmpty")
                .doesNotContain("hunter2-secret");
        assertThat(bindingResult.toString()).as("what used to be logged").contains("hunter2-secret");
    }

    @Test
    @DisplayName("a user form prints no password, whatever it holds")
    void aUserFormPrintsNoPassword() {
        UserDTO form = new UserDTO();
        form.setUsername("someone");
        form.setPassword("hunter2-secret");

        assertThat(form.toString()).contains("someone").doesNotContain("hunter2-secret");
    }

    @Test
    @DisplayName("a share token is masked wherever a path is written down, and every other path is left alone")
    void masksShareTokens() {
        assertThat(GlobalGeneralLogging.maskSecrets("/share/uy0-cu-zdSFh3DJVb-iK9bfwyFIw61o2OODH1nwAgic")).isEqualTo("/share/***");
        assertThat(GlobalGeneralLogging.maskSecrets("/app/share/abc?x=1")).isEqualTo("/app/share/***?x=1");
        assertThat(GlobalGeneralLogging.maskSecrets("/share/abc/more")).isEqualTo("/share/***/more");
        assertThat(GlobalGeneralLogging.maskSecrets("/share/")).isEqualTo("/share/");
        assertThat(GlobalGeneralLogging.maskSecrets("/files/share-links")).isEqualTo("/files/share-links");
        assertThat(GlobalGeneralLogging.maskSecrets("/resource/share-links/5")).isEqualTo("/resource/share-links/5");
        assertThat(GlobalGeneralLogging.maskSecrets(null)).isNull();
    }
}
