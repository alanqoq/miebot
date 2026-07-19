UPDATE bots
SET intents = 33554432,
    revision = revision + 1
WHERE intents = 0;
