/**
 * The JSON layer used by this application's own pages.
 *
 * <p>Everything here is called by jQuery from a Thymeleaf template, never by an outside client -
 * that is {@code com.hnp.filemanagement.api}. The two now answer identically: a mutation returns
 * {@link com.hnp.filemanagement.dto.ApiResult}, a lookup returns a DTO, and a failure is an
 * RFC 9457 problem document produced by {@code GlobalExceptionHandler}.
 *
 * <p>Three rules hold across this package:
 *
 * <ul>
 *   <li><b>No local try/catch.</b> Domain exceptions carry their own status - 404 for missing, 409
 *       for still-referenced, 400 for invalid, 417 for a business rule - and catching them locally
 *       flattened every failure to 400 with a hand-written sentence.</li>
 *   <li><b>Bodies are bound, not parsed.</b> Request records live in {@code dto}; the endpoints
 *       used to read the raw body with {@code JsonParserFactory} and threw
 *       {@code NullPointerException} when a key was missing.</li>
 *   <li><b>Success stays 200.</b> The pages branch only on {@code xhr.status === 200} and never
 *       read the response body, so failure statuses are free to be accurate but success is not.</li>
 * </ul>
 */
package com.hnp.filemanagement.resource;
