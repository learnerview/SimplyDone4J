package io.github.learnerview.simplydone4j.repository;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The single mapper used to turn a job or an execution log into its stored form.
 *
 * <p>This is deliberately <em>not</em> the application's mapper. The stored form is a
 * storage contract: the hash field names and the shape of the values are read back by the
 * library itself, by older versions of the library, and by anyone inspecting Redis
 * directly. Letting a host naming strategy (snake_case, kebab-case) or a host inclusion
 * rule rewrite that contract would mean the field name used to write a job no longer
 * matches the name used to read its lease, which silently disables the fencing check.</p>
 *
 * <p>Pinning the settings rather than relying on Jackson's defaults keeps the persisted
 * format stable across Jackson upgrades:</p>
 * <ul>
 *   <li>nulls are included, because a null is how the repository detects a field that
 *       must be deleted from the hash rather than overwritten;</li>
 *   <li>timestamps are written as ISO-8601 strings, matching what earlier versions
 *       stored, so an upgrade can read jobs written by the previous release;</li>
 *   <li>unknown hash fields are ignored, so a newer version of the library may add
 *       fields without an older reader failing on every job.</li>
 * </ul>
 *
 * <p>Payload serialization is a separate concern and stays on the application's mapper
 * (see {@code JobMapper}), because that is the codec the application chose.</p>
 */
final class PersistenceJson {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .changeDefaultPropertyInclusion(inclusion ->
                    inclusion.withValueInclusion(JsonInclude.Include.ALWAYS))
            .build();

    private PersistenceJson() {
    }

    static ObjectMapper mapper() {
        return MAPPER;
    }
}
