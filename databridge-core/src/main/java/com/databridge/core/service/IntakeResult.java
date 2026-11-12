package com.databridge.core.service;

import com.databridge.core.domain.DatasetVersion;
import com.databridge.core.schema.WorkbookSchema;

/** A recorded upload and, when it succeeded, its schema. */
public record IntakeResult(DatasetVersion dataset, WorkbookSchema schema) {
    public boolean succeeded() {
        return dataset.status() == com.databridge.core.run.RunStatus.SUCCEEDED;
    }
}
