package com.databridge.core.validation;

/** A rule that could not run in this check, and why (for example its sheet was not submitted). */
public record SkippedRule(String ruleId, String reason) { }
