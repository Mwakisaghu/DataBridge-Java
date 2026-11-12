package com.databridge.core.service;

import java.nio.file.Path;

class IntakeServiceTest extends IntakeServiceContract {
    @Override
    protected Fixture fixture(Path tmp) {
        return Fixture.inMemory(tmp);
    }
}
