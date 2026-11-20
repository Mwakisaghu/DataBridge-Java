package com.databridge.core.service;

import com.databridge.core.validation.RuleDef;

/** Lets tests in other packages run the Rule Setup's validation. */
public final class RuleServiceTestAccess {
    private RuleServiceTestAccess() { }

    public static void validate(RuleDef r) {
        RuleService.validate(r);
    }
}
