package com.databridge.core.service;

import com.databridge.core.setup.DataBridgeSetup;
import com.databridge.core.setup.SheetSetup;

import java.util.List;

/** Installation setups for export and import between environments. */
public record SetupBundle(DataBridgeSetup general, List<SheetSetup> templates) {
    public SetupBundle {
        templates = templates == null ? List.of() : List.copyOf(templates);
    }
}
