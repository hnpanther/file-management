package com.hnp.filemanagement.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an integration test that exercises the real service beans against a real database.
 *
 * <p>Two decisions are baked in here, and both are corrections of how the service tests used to
 * work.
 *
 * <p><b>The beans are Spring's, not {@code new}.</b> The previous tests constructed each service by
 * hand — {@code new GeneralTagService(entityManager, repository, actionHistoryService)} — which
 * produces an object with no proxy around it. Every {@code @Transactional} on the class under test
 * was therefore inert: the tests could not have caught a missing or misplaced transaction boundary,
 * which is exactly the class of bug that turned out to be there. Autowiring the real bean means the
 * annotations are live.
 *
 * <p><b>Each test rolls back.</b> {@link Transactional} on the test wraps it in a transaction that
 * is never committed, so no test can leave anything behind. The old tests marked every method
 * {@code @Commit} and cleaned up with a hand-written sequence of {@code deleteAll()} calls in
 * {@code @AfterEach} — in foreign-key order, so adding a table meant editing six teardowns, and
 * a test that failed part-way left rows behind that broke the next class to run.
 *
 * <p>The trade-off is that nothing here reaches a real commit, so a constraint that is only checked
 * at commit time would not fire. Where a test is about a constraint it flushes explicitly; see
 * {@code FileInfoRepositoryTest}.
 */
@Documented
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@SpringBootTest
@Transactional
public @interface ServiceIntegrationTest {
}
