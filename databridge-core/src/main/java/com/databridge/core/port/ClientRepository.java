package com.databridge.core.port;

import com.databridge.core.domain.Client;

import java.util.List;
import java.util.Optional;

public interface ClientRepository {
    void insert(Client client);
    Optional<Client> find(String id);
    Optional<Client> findByCode(String code);
    List<Client> list();
}
