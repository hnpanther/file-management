package com.hnp.filemanagement.util;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

/**
 * The Persian a page shows, read from {@code messages.properties} ({@code docs/issues.md}
 * issue 26, roadmap 2.1).
 *
 * <p>The controllers used to hold the sentences themselves - "اطلاعات با موفقیت ذخیره شد" written
 * out twelve times, "لطفا اطلاعات را بطور صحیح وارد نمایید" eleven - so a wording change meant
 * finding every copy, and a Java file was a place where translators had to look. The templates
 * have read their text from the bundle for a while; this is the same bundle for the text a
 * handler puts on the model.
 *
 * <p>A missing key throws rather than rendering the key: a sentence that never reached the bundle
 * should fail the test that renders the page, not appear in front of a person.
 */
@Component
public class UiMessages {

    private final MessageSource messageSource;

    public UiMessages(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    /** The message for this key, with {@code {0}}-style arguments filled in. */
    public String get(String code, Object... arguments) {
        return messageSource.getMessage(code, arguments, LocaleContextHolder.getLocale());
    }
}
