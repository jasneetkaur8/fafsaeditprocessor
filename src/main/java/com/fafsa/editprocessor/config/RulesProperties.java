package com.fafsa.editprocessor.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code fafsa.rules.*} settings.
 *
 * @param location   the rule file loaded at startup, e.g. {@code classpath:rules/fafsa-2026-27.yaml}
 *                   or {@code file:/etc/fafsa/rules.yaml}; override with {@code FAFSA_RULES_LOCATION}
 * @param storageDir where rule sets activated at runtime will be saved (not used yet)
 */
@ConfigurationProperties(prefix = "fafsa.rules")
public record RulesProperties(String location, String storageDir) {
}
