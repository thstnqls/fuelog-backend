ALTER TABLE vehicles ADD COLUMN vin_hash BINARY(32) NULL, ADD UNIQUE KEY uq_vehicle_vin_hash (vin_hash);
ALTER TABLE vehicle_installations ADD COLUMN vin_hash BINARY(32) NULL;
