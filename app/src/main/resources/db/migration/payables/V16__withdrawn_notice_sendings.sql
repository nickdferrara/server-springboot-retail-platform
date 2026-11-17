-- A sending recorded in error is withdrawn, which makes the notice unsent again. The withdrawn sending is kept
-- here, with who withdrew it, when, and why, so that what the vendor was told can be traced.
CREATE TABLE deduction_notice_withdrawn_sending
(
    deduction_notice_id UUID         NOT NULL REFERENCES deduction_notice (id) ON DELETE CASCADE,
    position            INT          NOT NULL,
    sent_on             DATE         NOT NULL,
    sent_by             VARCHAR(20)  NOT NULL,
    sent_to             VARCHAR(100) NOT NULL,
    sent_recorded_by    TEXT         NOT NULL,
    withdrawn_on        DATE         NOT NULL,
    withdrawn_by        TEXT         NOT NULL,
    reason              VARCHAR(200) NOT NULL,
    PRIMARY KEY (deduction_notice_id, position),
    CONSTRAINT deduction_notice_withdrawn_sending_channel_known
        CHECK (sent_by IN ('EMAIL', 'POST', 'IN_PERSON', 'OTHER'))
);
