package com.databridge.app;

import com.databridge.core.service.AuditLog;
import com.databridge.core.service.FindingService;
import com.databridge.core.service.IntakeService;
import com.databridge.core.service.MigrationService;
import com.databridge.core.service.RetentionService;
import com.databridge.core.service.RuleService;
import com.databridge.core.service.SetupService;
import com.databridge.core.security.UserService;

/** The core services the HTTP layer calls, wired once in {@link DataBridgeServer}. */
public record Services(SetupService setups, MigrationService migrations, IntakeService intake, RetentionService retention,
                       AuditLog audit, RuleService rules, FindingService findings, UserService users,
                       String authMode) { }
