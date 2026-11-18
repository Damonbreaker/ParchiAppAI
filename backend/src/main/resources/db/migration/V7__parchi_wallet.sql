-- Shopkeeper Parchi wallet: each store starts with 100 free Parchi credits.
-- 1% of the accepted quote total is deducted when an order is marked picked up (paid).

ALTER TABLE stores
    ADD COLUMN parchi_balance NUMERIC(12, 2) NOT NULL DEFAULT 100
        CONSTRAINT ck_stores_parchi_balance CHECK (parchi_balance >= 0);
