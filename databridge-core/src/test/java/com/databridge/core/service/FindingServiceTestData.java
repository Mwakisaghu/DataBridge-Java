package com.databridge.core.service;

/** Shares the members workbook used by FindingServiceTest with the server tests. */
public final class FindingServiceTestData {
    private FindingServiceTestData() { }

    public static byte[] members() throws Exception {
        return FindingServiceTest.members();
    }
}
