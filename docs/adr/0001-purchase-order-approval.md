# 1. Purchase order approval and segregation of duties

Date: 2026-11-11

## Status

Accepted

## Context

The PRD asks for two things that pull against each other: buyers "approve POs over thresholds", and "the user who
creates a purchase order cannot approve it". Read strictly, the second would send every order, however small, to
a second person. The PRD's lifecycle has no state between Draft and Approved.

## Decision

Every purchase order is approved before it is sent, and the approval policy decides who may give that approval.

- An order totalling at or under the approval threshold in effect may be approved by any user with the `Buyer`
  role, including the buyer who created it.
- An order over the threshold needs a user with the `Buyer.Approver` role who did not create the order.
- While no threshold is in effect, or when the order is in another currency than the threshold, the order is
  treated as over the threshold.
- The threshold is data owned by `purchasing`, effective dated in the company time zone and kept as history. It
  is set by a `Buyer.Approver`.

## Consequences

- Segregation of duties is enforced where the money is: above the threshold. Setting the threshold to zero sends
  every order to a second person, which is the strict reading of the PRD.
- A new installation has no threshold, so nothing can be self-approved until an approver sets one.
- There is one threshold for the company. Thresholds per buyer, vendor, or department would extend the policy, not
  the order lifecycle.
