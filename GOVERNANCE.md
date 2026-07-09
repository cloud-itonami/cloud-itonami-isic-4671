# Governance

`cloud-itonami-4920` is an OSS open-business blueprint for community freight
transport.

## Maintainers
Maintainers may merge changes that preserve these invariants:
- shipments with invalid tracking numbers can never dispatch.
- the Freight Governor remains independent of the advisor.
- hard policy violations (exception-suppression, force-settle) cannot be overridden by human approval.
- every dispatch, exception, settlement and POD path is auditable.
- customer, route and credentials data stays outside Git.

## Decision Records
Architecture decisions live in `docs/adr/`. Changes to the trust model, storage contract, public business model, operator certification or license should add or update an ADR.

## Operator Governance
Anyone may fork and operate independently. itonami.cloud certification is a separate trust mark and should require security, audit and data-flow review.

Certified operators can lose certification for:
- bypassing dispatch or settlement policy checks
- mishandling customer or route data
- misrepresenting certification status
- failing to respond to security incidents
