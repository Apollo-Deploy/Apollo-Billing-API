# Security Policy

Security is especially important for Apollo Billing because it processes billing state, subscription entitlements, usage records, service credentials, customer identifiers, and payment-provider webhooks.

We appreciate responsible reports from security researchers and users who help us protect the Apollo Deploy ecosystem.

## Supported Versions

Apollo Billing is currently under active development.

| Version                       | Supported   |
| ----------------------------- | ----------- |
| Latest release                | Yes         |
| `main` branch                 | Best effort |
| Older releases                | No          |
| Forks or modified deployments | No          |

Security fixes are applied to the latest maintained release. Users may be required to upgrade to receive a fix.

## Reporting a Vulnerability

**Do not report suspected vulnerabilities through public GitHub issues, discussions, pull requests, social media, or other public channels.**

Report vulnerabilities privately using GitHub's private vulnerability reporting:

https://github.com/Apollo-Deploy/Apollo-Billing-API/security/advisories/new

If private reporting is unavailable, contact an Apollo Deploy repository maintainer through GitHub and request a private communication channel. Do not include vulnerability details in the initial public message.

For suspected credential exposure, leaked secrets, or an actively exploited vulnerability, clearly mark the report as **urgent**.

## What to Include

Provide enough information for us to reproduce and assess the issue:

* A clear description of the vulnerability.
* The affected endpoint, component, file, dependency, or configuration.
* The affected commit, branch, release, or deployment version.
* Reproduction steps or a minimal proof of concept.
* The expected behaviour and the observed behaviour.
* The security impact and realistic attack scenario.
* Any prerequisites, required permissions, or environmental conditions.
* Relevant logs, request and response samples, screenshots, or traces.
* Suggested mitigations or fixes, when available.
* Your preferred name and attribution details, if you want public credit.

Remove or redact access tokens, credentials, personal information, payment information, and unrelated customer data from all reports.

## Sensitive Areas

Reports involving the following areas are particularly valuable:

* Authentication or authorisation bypasses.
* OAuth 2.1 service-to-service authentication and JWT validation.
* Cross-organisation or cross-tenant data access.
* Billing entitlement or quota bypasses.
* Unauthorised subscription, invoice, customer, or payment-method access.
* Usage manipulation, replay, duplication, or incorrect metering.
* Polar webhook signature validation, replay protection, or event handling.
* Server-side request forgery, injection, path traversal, or remote code execution.
* Exposure of API keys, service credentials, database credentials, signing keys, webhook secrets, or customer data.
* Unsafe redirect handling or checkout-session manipulation.
* Privilege escalation.
* Vulnerable dependencies with a demonstrated impact on Apollo Billing.
* Security-sensitive race conditions or idempotency failures.
* Denial-of-service issues with a practical and repeatable attack path.

## Response Targets

We aim to follow these timelines:

| Stage                   | Target                           |
| ----------------------- | -------------------------------- |
| Initial acknowledgement | Within 3 business days           |
| Initial triage          | Within 7 business days           |
| Status updates          | At least every 14 days           |
| Resolution              | Based on severity and complexity |
| Coordinated disclosure  | After a fix is available         |

These are targets rather than guarantees. Complex issues, third-party dependencies, and coordinated releases may require additional time.

If you do not receive an acknowledgement within 7 business days, you may send a follow-up through the same private advisory.

## Disclosure Process

After receiving a report, we will:

1. Confirm receipt and establish a private communication channel.
2. Reproduce and assess the vulnerability.
3. Determine severity, affected versions, and required mitigations.
4. Develop and test a fix.
5. Prepare release notes and upgrade guidance where appropriate.
6. Coordinate a disclosure date with the reporter.
7. Publish a GitHub Security Advisory and request a CVE when appropriate.
8. Credit the reporter if requested and permitted.

Please allow us a reasonable opportunity to investigate and release a fix before publicly disclosing the vulnerability.

We will not request that a reporter keep an unresolved vulnerability secret indefinitely. If we cannot agree on a disclosure date, we ask for at least 90 days from the date of acknowledgement, except when active exploitation or immediate public risk requires a shorter timeline.

## Severity

We evaluate severity using technical impact, exploitability, affected users, required privileges, tenant isolation, financial impact, data exposure, and availability impact.

We may use CVSS as an input, but the final severity may also account for Apollo Billing's architecture and deployment model.

Typical severity examples:

| Severity | Examples                                                                                                                              |
| -------- | ------------------------------------------------------------------------------------------------------------------------------------- |
| Critical | Remote code execution, signing-key compromise, broad authentication bypass, or cross-tenant compromise without user interaction       |
| High     | Privilege escalation, significant customer-data exposure, billing enforcement bypass, or unauthorised subscription/payment operations |
| Medium   | Limited data exposure, constrained authorisation issues, practical denial of service, or webhook replay with material impact          |
| Low      | Minor information disclosure, hardening gaps, or issues requiring unlikely conditions with limited impact                             |

## Safe Harbour

When conducting security research in accordance with this policy, we will consider your research to be:

* Authorised concerning applicable anti-hacking laws.
* Authorised concerning circumvention restrictions, to the extent legally permitted.
* Exempt from restrictions in our terms that would prevent good-faith security research.
* Conducted in good faith, provided you make a reasonable effort to avoid privacy violations, service disruption, data destruction, and harm to users.

We will not initiate legal action against researchers who act in good faith and follow this policy.

This safe harbour does not authorise activity against third-party services, infrastructure, accounts, or data. You remain responsible for complying with applicable law.

If you are unsure whether planned testing is permitted, contact us privately before proceeding.

## Testing Guidelines

When testing Apollo Billing:

* Use accounts, organisations, data, credentials, and infrastructure you own or are explicitly authorised to test.
* Prefer local or isolated test environments.
* Use the minimum access and data required to demonstrate the issue.
* Stop testing once you have confirmed the vulnerability.
* Do not access, modify, retain, or share another user's data.
* Do not attempt to obtain persistence.
* Do not deploy malware or destructive payloads.
* Do not perform denial-of-service, load, or stress testing without written permission.
* Do not perform social engineering, phishing, or physical attacks.
* Do not target Apollo Deploy employees, contributors, customers, or service providers.
* Do not test third-party systems such as Polar, cloud providers, package registries, identity providers, or GitHub without their explicit permission.
* Do not use automated scanners in a way that degrades service availability.
* Securely delete any accidentally obtained sensitive data after reporting it.

## Out of Scope

The following generally do not qualify as security vulnerabilities unless they create a demonstrated and material security impact:

* Reports based only on automated scanner output.
* Missing security headers without a practical exploit.
* Missing rate limits without a demonstrated security impact.
* Self-XSS or attacks requiring users to execute arbitrary code manually.
* Clickjacking on pages without sensitive actions.
* Username, email, organisation, or resource enumeration with negligible impact.
* CSV or spreadsheet formula injection without a realistic execution path.
* Version disclosure, banner disclosure, or stack identification.
* Best-practice recommendations without an exploitable weakness.
* Vulnerabilities that affect only unsupported versions.
* Vulnerabilities in third-party services that do not originate from Apollo Billing.
* Dependency CVEs without evidence that the vulnerable functionality is reachable or exploitable in Apollo Billing.
* Denial-of-service testing conducted without prior written authorisation.
* Social engineering, phishing, spam, physical attacks, or credential stuffing.
* Previously reported issues or issues already known to maintainers.
* Publicly disclosed zero-day vulnerabilities before maintainers have had a reasonable opportunity to patch them.

## Secrets and Credential Exposure

If you discover a credential, token, private key, webhook secret, database password, or other secret in the repository or a published artefact:

1. Do not use the credential.
2. Do not test whether it grants access beyond the minimum necessary to establish that it appears valid.
3. Report it immediately through the private reporting channel.
4. Include the file, commit, package, image, or artefact where it was found.
5. Do not copy or redistribute the secret.

Credentials committed to Git history must be treated as compromised even if the file is later deleted. Maintainers should rotate or revoke the credential and review relevant access logs.

## Bug Bounties

Apollo Deploy does not currently operate a public bug bounty programme.

Submitting a report does not create an entitlement to payment. Any reward, merchandise, public acknowledgement, or other recognition is entirely discretionary unless a separate written bounty programme explicitly applies.

## Recognition

We are happy to credit researchers who submit valid reports and coordinate disclosure responsibly.

Tell us how you would like to be credited, including your name, handle, and optional link. Anonymous reports are also accepted.

We may withhold public attribution when required by law, when disclosure could increase risk, or when the reporter requests anonymity.

## Security Updates

Security updates may be published through:

* GitHub Security Advisories.
* Repository releases and release notes.
* Relevant Apollo Deploy communication channels.

Users should watch this repository and enable Dependabot or equivalent dependency monitoring for their deployments.

## Policy Changes

This policy may be updated as Apollo Billing and its security processes mature. The version committed to the default branch is the current policy.
