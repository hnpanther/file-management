package com.hnp.filemanagement.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.springframework.web.multipart.MultipartFile;

/**
 * {@code @ValidFile}: the upload is one of the accepted kinds, judged by extension and content.
 *
 * <p>Delegates to {@link ContentTypes}, which is also what the service consults when it stores
 * the file - so this is an early answer on the form and the v1 API, not the enforcement. Until
 * issue 12 was fixed this compared the client's declared {@code Content-Type} against a list,
 * which any client could satisfy by declaring whatever it liked.
 */
public class FileValidator implements ConstraintValidator<ValidFile, MultipartFile> {

    @Override
    public boolean isValid(MultipartFile file, ConstraintValidatorContext context) {
        if (file == null) {
            return false;
        }
        try {
            ContentTypes.detect(file);
            return true;
        } catch (com.hnp.filemanagement.exception.InvalidDataException e) {
            // The reason, not the annotation's default: a client is told which rule it broke.
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate(e.getMessage()).addConstraintViolation();
            return false;
        }
    }
}
