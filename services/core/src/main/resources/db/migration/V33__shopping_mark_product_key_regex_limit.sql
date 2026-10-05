ALTER TABLE shopping_marks DROP CONSTRAINT shopping_marks_product_key_check;
ALTER TABLE shopping_marks ADD CONSTRAINT shopping_marks_product_key_check
    CHECK (product_key ~ '^[a-zа-я0-9]+$');
