package com.hnp.filemanagement.entity;

import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.Hibernate;

/**
 * The identity every entity in this application shares: a generated {@code Integer} primary key,
 * and the three {@code Object} methods that JPA makes surprisingly easy to get wrong.
 *
 * <p>Every entity used to be annotated {@code @Data}, which generated all three over <em>all</em>
 * fields, including the associations. That produced three separate defects:
 *
 * <ul>
 *   <li>{@code toString()} recursed across a bidirectional pair — {@code FileInfo} calls
 *       {@code FileDetails} calls {@code FileInfo} — until the stack overflowed. Any log line or
 *       exception message that touched an entity crashed the request.</li>
 *   <li>{@code equals}/{@code hashCode} read every association, which initialised lazy collections
 *       just to answer a comparison, defeating the fetch plan.</li>
 *   <li>{@code hashCode} included the generated id, which is null before the insert and non-null
 *       after. An entity put into a {@code HashSet} before flush could not be found afterwards.</li>
 * </ul>
 *
 * <p>The replacements here are the standard JPA-safe forms:
 *
 * <ul>
 *   <li><b>{@code equals}</b> compares ids, and only when the id is set — two unsaved entities are
 *       equal only if they are the same object. {@code Hibernate.getClass} is used instead of
 *       {@code getClass()} because a lazy association hands you a proxy subclass, and a proxy must
 *       still equal the entity it stands for.</li>
 *   <li><b>{@code hashCode}</b> is constant per entity type. That is deliberate: a hash must not
 *       change when the id is assigned at insert time, and an entity's id is assigned late.
 *       Collections of mixed types are not a real use case here, so the collision cost is nil.</li>
 *   <li><b>{@code toString}</b> prints the type and the id and nothing else, so it can never walk
 *       an association or trigger a lazy load.</li>
 * </ul>
 *
 * <p>All three are {@code final}: an entity that overrides them re-opens exactly the bugs above.
 */
@MappedSuperclass
@Getter
@Setter
public abstract class AbstractEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;

    @Override
    public final boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (other == null || Hibernate.getClass(this) != Hibernate.getClass(other)) {
            return false;
        }
        Integer thisId = getId();
        // Two unsaved entities are never equal - they have no identity to compare yet.
        return thisId != null && thisId.equals(((AbstractEntity) other).getId());
    }

    @Override
    public final int hashCode() {
        return Hibernate.getClass(this).hashCode();
    }

    @Override
    public final String toString() {
        return Hibernate.getClass(this).getSimpleName() + "#" + getId();
    }
}
