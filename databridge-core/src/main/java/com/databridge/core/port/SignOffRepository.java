package com.databridge.core.port;

import com.databridge.core.domain.SignOff;

import java.util.Optional;

/** Run sign-offs, one per run (a rejected one is replaced by the next request). */
public interface SignOffRepository {
    Optional<SignOff> find(String runId);
    void save(SignOff s);
}
