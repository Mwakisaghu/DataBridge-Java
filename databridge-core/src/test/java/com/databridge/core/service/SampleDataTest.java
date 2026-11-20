package com.databridge.core.service;

import java.nio.file.Path;

class SampleDataTest extends SampleDataContract {
    @Override
    protected Fixture fixture(Path tmp) {
        return Fixture.inMemory(tmp);
    }
}
