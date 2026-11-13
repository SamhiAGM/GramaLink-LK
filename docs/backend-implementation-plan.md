# Backend implementation plan and workflow assumptions

Base: networking commit 0d76ad8; preserve its JSON/binary framing and canonical writers.

1. Add PostgreSQL schema/seeds, JDBC configuration/transactions and typed service contracts.
2. Implement BCrypt login, database-backed authorization, request workflow, versioned writes and atomic history/audit/notifications.
3. Add durable chat, notification recovery, admin APIs and authorized incremental file storage.
4. Inject a backend dispatcher into existing TCP handlers, add socket/real PostgreSQL integration tests and document setup/contracts.

## Proposed workflow (no existing state enum was supplied)

- Citizen CREATE -> SUBMITTED (version 0; assigned GN/DS selected from matching demo offices).
- Assigned GN RECEIVE: SUBMITTED -> RECEIVED_BY_GN.
- Assigned GN REVIEW: RECEIVED_BY_GN or RETURNED_TO_GN -> UNDER_GN_REVIEW.
- Assigned GN VERIFY: UNDER_GN_REVIEW -> VERIFIED_BY_GN.
- Assigned GN FORWARD: VERIFIED_BY_GN -> FORWARDED_TO_DS.
- Assigned GN REQUEST_CORRECTION: RECEIVED_BY_GN, UNDER_GN_REVIEW or VERIFIED_BY_GN -> NEEDS_CORRECTION.
- Owning citizen RESUBMIT: NEEDS_CORRECTION -> SUBMITTED, replacing title/description.
- Assigned DS RETURN: FORWARDED_TO_DS -> RETURNED_TO_GN; GN must review and verify again.
- Assigned DS APPROVE or REJECT: FORWARDED_TO_DS -> APPROVED or REJECTED.
- Assigned GN REJECT: RECEIVED_BY_GN, UNDER_GN_REVIEW or VERIFIED_BY_GN -> REJECTED.
- APPROVED and REJECTED are terminal. All mutations require expectedVersion. Correction, return and rejection require a reason.

Assumptions: users have one demo division; GN offices belong to a DS office; select one active GN and DS deterministically at creation and retain assignments. No automatic reassignment or self-selected roles. Admin can inspect all requests but cannot bypass officer state transitions. Admin changes that would strand existing assignments are rejected. Only synthetic data/documents are supported.

File policy defaults to PDF only, maximum 10 MiB. Backend completion is separate from TRANSPORT_RECEIVED; files become visible only after parsing, hash verification, final move and metadata commit. TCP is the existing academic LAN transport; no REST layer is added.
