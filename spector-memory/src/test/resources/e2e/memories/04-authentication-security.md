# Authentication & Security Memories

---
id: auth-001
type: EPISODIC
source: OBSERVED
tags: security, auth, jwt
valence: 0
---
JWT tokens are issued with a 15-minute access token lifetime and 7-day refresh token lifetime. Tokens include user roles, tenant ID, and a custom claim for feature flags. HMAC-SHA256 signing with rotation every 90 days.

---
id: auth-002
type: EPISODIC
source: OBSERVED
tags: security, auth, oauth
valence: 5
---
Integrated OAuth2 login with Google and GitHub providers using Spring Security OAuth2 Client. Users can link multiple social accounts to a single internal account. First-time social login auto-provisions an account with the VIEWER role.

---
id: auth-003
type: EPISODIC
source: OBSERVED
tags: security, vulnerability, error
valence: -35
---
Critical security vulnerability discovered: the password reset endpoint accepted any email without rate limiting, enabling account enumeration. Fixed by implementing constant-time response regardless of whether the email exists, plus rate limiting to 3 requests per email per hour.

---
id: auth-004
type: PROCEDURAL
source: REFLECTED
tags: security, auth, procedure, jwt
valence: 10
---
When implementing JWT validation: 1) Always verify the signature before reading claims. 2) Check the exp claim for expiration. 3) Validate the iss (issuer) and aud (audience) claims. 4) Use a clock skew tolerance of 30 seconds for distributed systems.

---
id: auth-005
type: EPISODIC
source: OBSERVED
tags: security, auth, rbac
valence: 5
---
Role-based access control implemented with four roles: VIEWER (read-only), EDITOR (read+write), ADMIN (full access), and SUPER_ADMIN (system configuration). Permissions are hierarchical — higher roles inherit all lower-role permissions.

---
id: auth-006
type: EPISODIC
source: OBSERVED
tags: security, encryption, data
valence: 0
---
Sensitive data at rest encrypted using AES-256-GCM with envelope encryption. Data encryption keys (DEK) are encrypted by a key encryption key (KEK) stored in AWS KMS. Key rotation happens automatically every 365 days.

---
id: auth-007
type: EPISODIC
source: OBSERVED
tags: security, auth, session, error
valence: -20
---
Session fixation attack vector found during penetration testing. The session ID was not regenerated after authentication. Fixed by calling httpSession.invalidate() before creating a new session post-login. Added CSP headers to prevent XSS.

---
id: auth-008
type: PROCEDURAL
source: REFLECTED
tags: security, procedure, api-key
valence: 5
---
API key management procedure: 1) Generate 256-bit random key using SecureRandom. 2) Store bcrypt hash in database, never plain text. 3) Return key only once at creation time. 4) Implement key rotation with grace period of 24 hours for old key.
