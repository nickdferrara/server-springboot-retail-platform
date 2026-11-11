-- Events already handled by listeners in this module. Every module that listens to events has the same table in
-- its own schema.

CREATE TABLE processed_event
(
    event_id     UUID                     NOT NULL,
    listener     TEXT                     NOT NULL,
    processed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (event_id, listener)
);
