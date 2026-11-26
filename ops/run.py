"""Reads Supabase Auth settings, applies ops/auth_patch.json, stores a report in private."OpsReport".
Only the plain settings listed in KEEP and the NAMES of the Edge Function secrets go into the report;
nothing from the API is printed (the Actions log of a public repo is public)."""
import json, os, sys, urllib.request, urllib.error

REF = os.environ["PROJECT_REF"]
TOKEN = os.environ["SUPABASE_ACCESS_TOKEN"]
API = f"https://api.supabase.com/v1/projects/{REF}"

KEEP = [
    "external_email_enabled", "external_phone_enabled", "external_anonymous_users_enabled", "external_google_enabled",
    "disable_signup", "mailer_autoconfirm", "sms_autoconfirm", "sms_otp_exp", "sms_otp_length", "sms_max_frequency",
    "sms_provider", "rate_limit_sms_sent", "rate_limit_verify", "rate_limit_otp", "rate_limit_token_refresh",
    "rate_limit_email_sent", "rate_limit_anonymous_users", "security_captcha_enabled", "security_captcha_provider",
    "jwt_exp", "refresh_token_rotation_enabled", "security_refresh_token_reuse_interval", "sessions_timebox",
    "sessions_inactivity_timeout", "sessions_single_per_user", "hook_send_sms_enabled",
    "hook_custom_access_token_enabled", "mfa_phone_enroll_enabled", "mfa_phone_verify_enabled",
    "mfa_totp_enroll_enabled", "password_min_length", "site_url", "mailer_otp_exp",
]


def call(method, path, body=None):
    req = urllib.request.Request(
        API + path, method=method,
        data=None if body is None else json.dumps(body).encode(),
        headers={"Authorization": "Bearer " + TOKEN, "Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            raw = r.read()
            return r.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as e:
        return e.code, None


def pick(cfg):
    if not isinstance(cfg, dict):
        return None
    out = {k: cfg.get(k) for k in KEEP if k in cfg}
    out["sms_test_numbers_set"] = bool(cfg.get("sms_test_otp"))  # whether review/test numbers exist (not which)
    return out


s1, before = call("GET", "/config/auth")
patch = json.load(open("ops/auth_patch.json"))
s2 = None
if patch:
    s2, _ = call("PATCH", "/config/auth", patch)
s3, after = call("GET", "/config/auth")
s4, secrets = call("GET", "/secrets")
names = sorted(str(x.get("name")) for x in (secrets or []) if isinstance(x, dict))
report = {
    "read_status": s1, "patch": patch, "patch_status": s2, "read_after_status": s3, "secrets_status": s4,
    "before": pick(before), "after": pick(after), "secret_names": names,
}
q = 'insert into private."OpsReport" (kind, data) values (\'auth_config\', $tfops$' + json.dumps(report) + '$tfops$::jsonb)'
s5, _ = call("POST", "/database/query", {"query": q})
print(f"settings read {s1}, change {s2}, read again {s3}, secret names {s4}, report saved {s5}")
sys.exit(0 if s5 in (200, 201) else 1)
