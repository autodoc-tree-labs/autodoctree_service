ALTER TABLE user_rule
ADD COLUMN IF NOT EXISTS rule_effect VARCHAR(8) NOT NULL DEFAULT 'HARD';

UPDATE user_rule
SET rule_effect = 'HARD'
WHERE rule_effect IS NULL OR TRIM(rule_effect) = '';
