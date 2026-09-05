package com.hnp.filemanagement.dto;

/**
 * Body of the "change state" calls: {@code {"newState": 0}}.
 *
 * <p>The endpoints used to take the raw body as a {@code String} and pull the field out with
 * {@code JsonParserFactory}, which meant a missing field threw {@code NullPointerException} on
 * {@code map.get("newState").toString()} and answered 500. Binding it is both shorter and honest
 * about what the endpoint accepts.
 *
 * <p>Unknown fields are ignored - the pages send a little junk alongside the real field - so this
 * record deliberately does not mirror the whole payload.
 *
 * @param newState 0 for active, -1 for disabled; validated by the service, not here
 */
public record StateChangeRequest(Integer newState) {
}
