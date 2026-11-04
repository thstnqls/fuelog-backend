ALTER TABLE vehicle_installations
    ADD UNIQUE KEY uq_installation_credential_hash (credential_hash);
