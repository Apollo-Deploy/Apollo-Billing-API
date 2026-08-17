#!/usr/bin/env bash
# signal-catalog.sh — Canonical product/pricing constants for the Signal Polar catalog.
#
# Sourced by setup-signal.sh. Do NOT execute directly.
# All prices are in USD cents unless the variable name ends in _RATE (fractional dollar string).
# "custom" is a sentinel meaning the Polar product uses a custom/negotiated price.

# ── Namespaces ────────────────────────────────────────────────────────────────

NAMESPACE_SANDBOX="apollo-signal-sandbox-v1"
NAMESPACE_PRODUCTION="apollo-signal-v1"

# ── Polar API base URLs ───────────────────────────────────────────────────────

POLAR_URL_SANDBOX="https://sandbox-api.polar.sh"
POLAR_URL_PRODUCTION="https://api.polar.sh"

# ══════════════════════════════════════════════════════════════════════════════
# EMAIL — Meters
# ══════════════════════════════════════════════════════════════════════════════

EMAIL_METER_KEY="meter-signal-emails"
EMAIL_METER_OUTPUT_KEY="email"
EMAIL_METER_NAME="Signal Emails"
EMAIL_METER_EVENT="signal.email.sent"
EMAIL_METER_LABEL="email"
EMAIL_METER_MULTIPLIER="1000"   # 1 unit = 1000 emails (billing in thousands)

AUTOMATION_METER_KEY="meter-signal-automation-runs"
AUTOMATION_METER_OUTPUT_KEY="automation"
AUTOMATION_METER_NAME="Signal Automation Runs"
AUTOMATION_METER_EVENT="signal.automation.run"
AUTOMATION_METER_LABEL="run"
AUTOMATION_METER_MULTIPLIER="1000"

AI_CREDIT_METER_KEY="meter-signal-ai-credits"
AI_CREDIT_METER_OUTPUT_KEY="aiCredit"
AI_CREDIT_METER_NAME="Signal AI Credits"
AI_CREDIT_METER_EVENT="signal.ai.credit.used"
AI_CREDIT_METER_LABEL="credit"
AI_CREDIT_METER_MULTIPLIER="1"

# ══════════════════════════════════════════════════════════════════════════════
# EMAIL — Base plans
# setup_plan args: plan slug included_emails price_cents overage_rate_cents included_ai_credits visibility description
# ══════════════════════════════════════════════════════════════════════════════

# Spark (free)
PLAN_SPARK_NAME="Spark"
PLAN_SPARK_SLUG="signal-spark"
PLAN_SPARK_EMAILS="3000"
PLAN_SPARK_PRICE="0"
PLAN_SPARK_OVERAGE_RATE=""
PLAN_SPARK_AI_CREDITS="5"
PLAN_SPARK_VISIBILITY="public"
PLAN_SPARK_DESC="3,000 emails and 5 AI credits included. Free plan."

# Ignite — $15/mo
PLAN_IGNITE_NAME="Ignite"
PLAN_IGNITE_SLUG="signal-ignite"
PLAN_IGNITE_EMAILS="50000"
PLAN_IGNITE_PRICE="1500"
PLAN_IGNITE_OVERAGE_RATE="0.05"   # $0.50/1k = $0.0005/email; Polar unit = per-email so 0.05 cents
PLAN_IGNITE_AI_CREDITS="20"
PLAN_IGNITE_VISIBILITY="public"
PLAN_IGNITE_DESC="50,000 emails and 20 AI credits included. \$0.50 per 1k email overage."

# Growth — $35/mo
PLAN_GROWTH_NAME="Growth"
PLAN_GROWTH_SLUG="signal-growth"
PLAN_GROWTH_EMAILS="150000"
PLAN_GROWTH_PRICE="3500"
PLAN_GROWTH_OVERAGE_RATE="0.042"
PLAN_GROWTH_AI_CREDITS="50"
PLAN_GROWTH_VISIBILITY="public"
PLAN_GROWTH_DESC="150,000 emails and 50 AI credits included. \$0.42 per 1k email overage."

# Pulse — $65/mo
PLAN_PULSE_NAME="Pulse"
PLAN_PULSE_SLUG="signal-pulse"
PLAN_PULSE_EMAILS="300000"
PLAN_PULSE_PRICE="6500"
PLAN_PULSE_OVERAGE_RATE="0.036"
PLAN_PULSE_AI_CREDITS="100"
PLAN_PULSE_VISIBILITY="public"
PLAN_PULSE_DESC="300,000 emails and 100 AI credits included. \$0.36 per 1k email overage."

# Scale — $180/mo
PLAN_SCALE_NAME="Scale"
PLAN_SCALE_SLUG="signal-scale"
PLAN_SCALE_EMAILS="1000000"
PLAN_SCALE_PRICE="18000"
PLAN_SCALE_OVERAGE_RATE="0.03"
PLAN_SCALE_AI_CREDITS="250"
PLAN_SCALE_VISIBILITY="public"
PLAN_SCALE_DESC="1,000,000 emails and 250 AI credits included. \$0.30 per 1k email overage."

# Enterprise — custom
PLAN_ENTERPRISE_NAME="Enterprise"
PLAN_ENTERPRISE_SLUG="signal-enterprise"
PLAN_ENTERPRISE_EMAILS="custom"
PLAN_ENTERPRISE_PRICE="custom"
PLAN_ENTERPRISE_OVERAGE_RATE=""
PLAN_ENTERPRISE_AI_CREDITS="custom"
PLAN_ENTERPRISE_VISIBILITY="private"
PLAN_ENTERPRISE_DESC="Custom Signal contract. Configure manually after sales."

# ══════════════════════════════════════════════════════════════════════════════
# EMAIL — Add-ons and PAYG products
# ══════════════════════════════════════════════════════════════════════════════

DEDICATED_IP_SLUG="signal-dedicated-ip-addon"
DEDICATED_IP_NAME="Signal Dedicated IP"
DEDICATED_IP_PRICE="3000"   # $30/mo
DEDICATED_IP_DESC="Monthly dedicated IP add-on for an active Signal plan."

EMAIL_PAYG_SLUG="signal-email-payg"
EMAIL_PAYG_NAME="Signal Email PAYG"
EMAIL_PAYG_PRICE="0"
EMAIL_PAYG_OVERAGE_RATE="0.05"   # $0.50/1k emails
EMAIL_PAYG_DESC="Pay as you go emails at \$0.50 per 1k emails."

AUTOMATION_PAYG_SLUG="signal-automation-payg"
AUTOMATION_PAYG_NAME="Signal Automation PAYG"
AUTOMATION_PAYG_PRICE="0"
AUTOMATION_PAYG_OVERAGE_RATE="0.1"   # $1.00/1k runs
AUTOMATION_PAYG_DESC="Pay as you go automation runs at \$1.00 per 1k runs."

# ══════════════════════════════════════════════════════════════════════════════
# EMAIL — Automation run packs
# setup_pack args: name slug runs price_cents description
# ══════════════════════════════════════════════════════════════════════════════

PACK_AUTO_SMALL_NAME="Automation Small Pack"
PACK_AUTO_SMALL_SLUG="signal-automation-small-pack"
PACK_AUTO_SMALL_RUNS="10000"
PACK_AUTO_SMALL_PRICE="1000"
PACK_AUTO_SMALL_DESC="10,000 automation runs. \$1.00 per 1k."

PACK_AUTO_MEDIUM_NAME="Automation Medium Pack"
PACK_AUTO_MEDIUM_SLUG="signal-automation-medium-pack"
PACK_AUTO_MEDIUM_RUNS="50000"
PACK_AUTO_MEDIUM_PRICE="3000"
PACK_AUTO_MEDIUM_DESC="50,000 automation runs. \$0.60 per 1k."

PACK_AUTO_GROWTH_NAME="Automation Growth Pack"
PACK_AUTO_GROWTH_SLUG="signal-automation-growth-pack"
PACK_AUTO_GROWTH_RUNS="100000"
PACK_AUTO_GROWTH_PRICE="5500"
PACK_AUTO_GROWTH_DESC="100,000 automation runs. \$0.55 per 1k."

PACK_AUTO_SCALE_NAME="Automation Scale Pack"
PACK_AUTO_SCALE_SLUG="signal-automation-scale-pack"
PACK_AUTO_SCALE_RUNS="500000"
PACK_AUTO_SCALE_PRICE="20000"
PACK_AUTO_SCALE_DESC="500,000 automation runs. \$0.40 per 1k."

# ══════════════════════════════════════════════════════════════════════════════
# EMAIL — AI credit packs
# setup_ai_credit_pack args: name slug credits price_cents description
# ══════════════════════════════════════════════════════════════════════════════

PACK_AI_100_NAME="AI Credits 100"
PACK_AI_100_SLUG="signal-ai-credits-100"
PACK_AI_100_CREDITS="100"
PACK_AI_100_PRICE="300"
PACK_AI_100_DESC="100 AI credits. Credits expire at the end of the monthly period."

PACK_AI_500_NAME="AI Credits 500"
PACK_AI_500_SLUG="signal-ai-credits-500"
PACK_AI_500_CREDITS="500"
PACK_AI_500_PRICE="1500"
PACK_AI_500_DESC="500 AI credits. Credits expire at the end of the monthly period."

PACK_AI_1000_NAME="AI Credits 1000"
PACK_AI_1000_SLUG="signal-ai-credits-1000"
PACK_AI_1000_CREDITS="1000"
PACK_AI_1000_PRICE="3000"
PACK_AI_1000_DESC="1,000 AI credits. Credits expire at the end of the monthly period."
