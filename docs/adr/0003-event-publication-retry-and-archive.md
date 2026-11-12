# 3. Retrying, watching, and archiving event publications

Date: 2026-11-12

## Status

Accepted

## Context

Modules integrate through events recorded in the Spring Modulith event publication registry. A listener that
throws, or an instance that stops while a listener runs, leaves a publication incomplete. Procure to pay is a
chain of listeners (receiving, then inventory, payables, and the ledger), so one incomplete publication leaves
stock, the accrual, and the journal entry disagreeing. The PRD asks for failed publications to be retried on a
schedule, stale ones to be marked failed and alerted on, completed ones to be archived after 30 days, and
failures to be visible within 15 minutes. The application runs on two or more instances.

## Decision

- **Staleness.** The registry's own staleness monitor runs every minute and marks a publication failed when it
  has been published for 5 minutes without its listener starting, or processing or resubmitted for 10 minutes
  without completing, counted from its last delivery attempt (`spring.modulith.events.staleness`). This is how a
  publication orphaned by a stopped instance is found, so publications are not republished at startup.
- **Retry.** Every minute `EventPublicationRetrySchedule` resubmits failed publications through the registry's
  resubmission API. A publication is retried every run until its listener completes: there is no backoff and no
  limit on attempts, because the usual cause is an event that arrived before one it depends on, and a
  publication that is given up on would need a person to resubmit it.
- **Several instances.** No lock is taken. The registry claims a publication with a conditional update, so one
  instance resubmits it. A listener may still run twice, when a slow listener is declared stale while it is
  still running; every listener records the event in its module's `processed_event` table, so the second run
  waits for the first and then does nothing.
- **Watching.** Every minute `EventPublicationMonitor` counts incomplete and failed publications, publishes
  them and the age of the oldest as gauges (`retail.events.publications.*`), and logs an error naming the
  listeners with a publication incomplete for more than 15 minutes (`retail.events.monitor.overdue-after`).
- **Archive.** Completed publications stay in the registry for 30 days, then a nightly run moves them to
  `platform.event_publication_archive`, in batches of one statement each that skip rows another instance has
  locked. The archive table has the layout of Spring Modulith's own archive table.

## Consequences

- A listener that can never succeed for an event is retried every minute and reported every minute until
  someone fixes the cause. That is deliberate noise; backoff and a manual resubmission endpoint are deferred.
- The gauges and the error log are what an alert rule needs, but no alert rule exists yet: metrics are not
  exported to Azure Monitor and nothing is deployed.
- A listener slower than 10 minutes will be run a second time. Listeners must stay idempotent and short.
- The archive grows without limit. How long archived publications are kept is undecided.
- The registry's routine log lines (three a minute from the staleness monitor) are silenced below warning level.
