ALTER TABLE receipts
    ADD COLUMN category_code varchar(64)
        CHECK (category_code IN ('еда', 'транспорт', 'жилье', 'досуг', 'одежда', 'здоровье', 'работа', 'техника', 'долги', 'прочее')),
    ADD COLUMN category_source varchar(16) NOT NULL DEFAULT 'unknown'
        CHECK (category_source IN ('rule', 'model', 'default', 'unknown', 'human')),
    ADD COLUMN category_algorithm_version varchar(64) NOT NULL DEFAULT 'receipt-category.v1',
    ADD COLUMN alcohol_share numeric(24,4) CHECK (alcohol_share >= 0),
    ADD COLUMN leisure_share numeric(24,4) CHECK (leisure_share >= 0),
    ADD COLUMN leisure boolean NOT NULL DEFAULT false;
