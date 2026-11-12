package com.databridge.core.port;

import com.databridge.core.setup.DataBridgeSetup;
import com.databridge.core.setup.SheetSetup;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Stored installation setups. When nothing is stored, the shipped defaults apply.
 * Stored Template Setups override the shipped ones sheet by sheet.
 */
public interface SetupRepository {
    Optional<DataBridgeSetup> loadGeneral();
    void saveGeneral(DataBridgeSetup setup, String actor, Instant at);
    List<SheetSetup> loadSheets();
    void saveSheet(SheetSetup sheet, String actor, Instant at);
    boolean deleteSheet(String sheetName);
}
