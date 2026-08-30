# Role-Based Access Control — Design Rationale

Sunrise Dental's assessment scenario allows reasonable assumptions about access control
where they are well explained. A single shared login for all clinic staff does not reflect
how a real private dental clinic operates, so four roles were introduced, matching the
distinct jobs actually performed at the front desk and chairside.

## Roles

**Administrator** — responsible for system configuration and staff management: creating
and deactivating staff accounts, assigning roles, and managing the dentist/treatment
reference data the rest of the system depends on. Full access is appropriate because the
administrator is accountable for the system as a whole.

**Receptionist** — responsible for appointments, patient registration, and day-to-day
front-desk operations: booking, searching and billing appointments. Receptionists do not
manage staff accounts or treatment pricing, since those are administrative concerns
outside their job function, not clinical or front-desk ones.

**Dentist** — responsible for their own appointments and the patients assigned to those
appointments. A dentist has no legitimate need to browse another dentist's patient list or
administrative data, so access is restricted to appointments where `dentist_id` matches
their own linked dentist record. This limits the blast radius of a compromised or misused
dentist account to that dentist's own patients only.

**Billing** — responsible for financial operations (generating and viewing bills) without
administrative or clinical privileges. Billing staff can see enough patient/appointment
context to produce an accurate bill, but cannot register appointments or edit patient
records, which are reception's responsibility.

## Why this improves the system

This directly addresses the assessment's stated problems: accountability (every
appointment records which staff member created it via `created_by`), reduced risk of
accidental or malicious data exposure (a dentist account cannot pull an arbitrary
patient's information), and clearer operational boundaries between front-desk, clinical
and financial work — mirroring how the paper-based process split those responsibilities
in practice.

## Known limitations (documented, not fixed in this branch)

- The admin-protection check (last-active-admin) is a check-then-act read followed by a
  write, not wrapped in a row-locking transaction. Two simultaneous requests demoting two
  different admins at the exact same moment could theoretically both pass the check. Given
  this is a single-admin-workstation clinic tool, not a high-concurrency system, this is an
  accepted simplification rather than a fix left undone.
- Staff creation/update endpoints (`PUT /api/staff/{id}`) take `role`/`dentistId`/`active`
  as query-string parameters rather than a request body. The Jakarta Servlet API only
  auto-parses form-encoded bodies for `POST`, not `PUT`; parsing a raw `PUT` body manually
  was judged unnecessary complexity for what is currently a backend test API, not the
  final frontend contract.
- No separate audit log table was introduced beyond the existing `appointments.created_by`
  column. A full audit trail (who changed which staff account and when) was judged out of
  scope for this RBAC branch per the assignment's guidance not to over-engineer; it would
  be a reasonable addition to note as future work.
