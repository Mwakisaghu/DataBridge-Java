package com.databridge.core.service;

import com.databridge.core.domain.ValidationCheck;
import com.databridge.core.validation.ValidationResult;

import java.util.List;

/** The datasets recorded for a processed submission and the validation check run over them. */
public record SubmissionResult(List<IntakeResult> datasets, ValidationCheck check, ValidationResult validation) { }
