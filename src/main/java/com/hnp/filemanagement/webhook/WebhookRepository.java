package com.hnp.filemanagement.webhook;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** The subscriptions (roadmap 13.18). Few: read whole, for the page and for the rules held in memory. */
public interface WebhookRepository extends JpaRepository<Webhook, Integer> {

    List<Webhook> findAllByOrderByNameAsc();

    Optional<Webhook> findByNameIgnoreCase(String name);
}
