# Security

Lin can read notifications, the screen, contacts, the calendar and photos, and act on the phone.
Security reports are taken seriously.

## Reporting a vulnerability

Please **do not open a public issue**. Use GitHub's private reporting instead:
**Security → Report a vulnerability** on this repository. Include what an attacker could do, the
steps to reproduce, and the Lin version.

You can expect an acknowledgement within a week. Fixes ship in the next build, and the report is
credited in the release notes unless you would rather it was not.

## Supported versions

Only the latest build (`lin-dev`, and the newest numbered release) receives fixes.

## Scope

In scope: the app in this repository and its build workflows. Examples: a way for a web page,
notification, message or MCP server to make Lin act without the person's consent; leaking
conversations, memories or keys; bypassing the confirmation for messages and calls.

Out of scope: vulnerabilities in the models themselves (for example a model being talked into
saying something), in Android, or in servers you run yourself.
