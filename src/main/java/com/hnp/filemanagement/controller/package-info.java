/**
 * The HTML layer: Spring MVC controllers that render Thymeleaf pages.
 *
 * <p>These deliberately do not behave like the REST layer. A page controller catches its domain
 * exceptions and re-renders the same form with {@code showMessage}, {@code valid} and
 * {@code message} in the model, because the user must get their input back with an explanation
 * next to it - a 409 with an empty screen would lose the form. The REST layer, having no form to
 * preserve, lets the exceptions travel to {@code GlobalExceptionHandler}.
 *
 * <p>Every handler here follows the same four rules, and a new one must too:
 *
 * <ol>
 *   <li>a constant in {@code PermissionEnum}, named in a comment above the method;</li>
 *   <li>{@code @PreAuthorize("hasAuthority('X') || hasAuthority('ADMIN')")};</li>
 *   <li>an {@code actionHistoryService.saveActionHistory(...)} call for any mutation, written by
 *       the service rather than the controller;</li>
 *   <li>the {@code globalGeneralLogging.controllerLogging(...)} preamble.</li>
 * </ol>
 *
 * <p>The Persian strings assigned to {@code message} are still hardcoded here rather than read
 * from {@code messages.properties}; the templates were converted, these were not. It is issue 26
 * in {@code docs/issues.md} and belongs to Phase 2, not to a cleanup pass.
 */
package com.hnp.filemanagement.controller;
