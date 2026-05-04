-- No schema change needed if paymentMethod is stored as VARCHAR via @Enumerated(EnumType.STRING)
-- The enum values are: CASH, CLICK, PAYME, UZUM, TERMINAL, BANK, OTHER
-- Old values (CARD, BANK_TRANSFER, ONLINE) will remain in existing rows.
-- Update them if needed:
UPDATE payments SET payment_method = 'OTHER' WHERE payment_method = 'ONLINE';
UPDATE payments SET payment_method = 'TERMINAL' WHERE payment_method = 'CARD';
UPDATE payments SET payment_method = 'BANK' WHERE payment_method = 'BANK_TRANSFER';
