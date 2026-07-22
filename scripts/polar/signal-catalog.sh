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

# ══════════════════════════════════════════════════════════════════════════════
# SMS — Meters
# ══════════════════════════════════════════════════════════════════════════════

SMS_SEGMENT_METER_KEY="meter-signal-sms-segments"
SMS_SEGMENT_METER_OUTPUT_KEY="smsSegment"
SMS_SEGMENT_METER_NAME="Signal SMS Segments"
SMS_SEGMENT_METER_EVENT="signal.sms.segment_sent"
SMS_SEGMENT_METER_LABEL="segment"
SMS_SEGMENT_METER_MULTIPLIER="1"

MMS_MESSAGE_METER_KEY="meter-signal-mms-messages"
MMS_MESSAGE_METER_OUTPUT_KEY="mmsMessage"
MMS_MESSAGE_METER_NAME="Signal MMS Messages"
MMS_MESSAGE_METER_EVENT="signal.mms.message_sent"
MMS_MESSAGE_METER_LABEL="message"
MMS_MESSAGE_METER_MULTIPLIER="1"

# ══════════════════════════════════════════════════════════════════════════════
# SMS — Add-on subscription plans
# setup_sms_plan args: plan slug included_segments price_cents overage_rate_cents visibility description
# ══════════════════════════════════════════════════════════════════════════════

# SMS Lite — $10/mo
SMS_PLAN_LITE_NAME="SMS Lite"
SMS_PLAN_LITE_SLUG="signal-sms-lite"
SMS_PLAN_LITE_SEGMENTS="500"
SMS_PLAN_LITE_PRICE="1000"
SMS_PLAN_LITE_OVERAGE_RATE="0.015"   # $0.015/segment
SMS_PLAN_LITE_VISIBILITY="public"
SMS_PLAN_LITE_DESC="500 SMS segments/mo. \$0.015/segment overage (opt-in). Testing and low-volume alerts."

# SMS Starter — $20/mo
SMS_PLAN_STARTER_NAME="SMS Starter"
SMS_PLAN_STARTER_SLUG="signal-sms-starter"
SMS_PLAN_STARTER_SEGMENTS="1500"
SMS_PLAN_STARTER_PRICE="2000"
SMS_PLAN_STARTER_OVERAGE_RATE="0.014"
SMS_PLAN_STARTER_VISIBILITY="public"
SMS_PLAN_STARTER_DESC="1,500 SMS segments/mo. \$0.014/segment overage (opt-in). Small apps, OTP/2FA."

# SMS Growth — $45/mo
SMS_PLAN_GROWTH_NAME="SMS Growth"
SMS_PLAN_GROWTH_SLUG="signal-sms-growth"
SMS_PLAN_GROWTH_SEGMENTS="4000"
SMS_PLAN_GROWTH_PRICE="4500"
SMS_PLAN_GROWTH_OVERAGE_RATE="0.013"
SMS_PLAN_GROWTH_VISIBILITY="public"
SMS_PLAN_GROWTH_DESC="4,000 SMS segments/mo. \$0.013/segment overage (opt-in). Growing products, notifications."

# SMS Business — $95/mo
SMS_PLAN_BUSINESS_NAME="SMS Business"
SMS_PLAN_BUSINESS_SLUG="signal-sms-business"
SMS_PLAN_BUSINESS_SEGMENTS="9000"
SMS_PLAN_BUSINESS_PRICE="9500"
SMS_PLAN_BUSINESS_OVERAGE_RATE="0.012"
SMS_PLAN_BUSINESS_VISIBILITY="public"
SMS_PLAN_BUSINESS_DESC="9,000 SMS segments/mo. \$0.012/segment overage (opt-in). Marketing and transactional mix."

# SMS Scale — $195/mo
SMS_PLAN_SCALE_NAME="SMS Scale"
SMS_PLAN_SCALE_SLUG="signal-sms-scale"
SMS_PLAN_SCALE_SEGMENTS="18000"
SMS_PLAN_SCALE_PRICE="19500"
SMS_PLAN_SCALE_OVERAGE_RATE="0.011"
SMS_PLAN_SCALE_VISIBILITY="public"
SMS_PLAN_SCALE_DESC="18,000 SMS segments/mo. \$0.011/segment overage (opt-in). High-volume senders."

# SMS Enterprise — custom
SMS_PLAN_ENTERPRISE_NAME="SMS Enterprise"
SMS_PLAN_ENTERPRISE_SLUG="signal-sms-enterprise"
SMS_PLAN_ENTERPRISE_SEGMENTS="custom"
SMS_PLAN_ENTERPRISE_PRICE="custom"
SMS_PLAN_ENTERPRISE_OVERAGE_RATE="0.010"
SMS_PLAN_ENTERPRISE_VISIBILITY="private"
SMS_PLAN_ENTERPRISE_DESC="Custom SMS contract. Negotiate segments and overage. Dedicated support."

# ══════════════════════════════════════════════════════════════════════════════
# SMS — MMS add-on
# ══════════════════════════════════════════════════════════════════════════════

MMS_ADDON_SLUG="signal-mms-addon"
MMS_ADDON_NAME="Signal MMS Add-On"
MMS_ADDON_PRICE="800"             # $8/mo
MMS_ADDON_INCLUDED_MESSAGES="300"
MMS_ADDON_OVERAGE_RATE="0.04"     # $0.04/message
MMS_ADDON_DESC="300 MMS messages/mo included. \$0.04/message overage (opt-in). Requires an active SMS plan."

# ══════════════════════════════════════════════════════════════════════════════
# SMS — Premium recurring add-ons
# ══════════════════════════════════════════════════════════════════════════════

SMS_NUMBER_POOLING_SLUG="signal-sms-number-pooling"
SMS_NUMBER_POOLING_NAME="Signal Number Pooling"
SMS_NUMBER_POOLING_PRICE="1500"   # $15/mo
SMS_NUMBER_POOLING_DESC="Monthly number pooling add-on. Requires SMS Business+ plan."

SMS_SHORT_CODE_RANDOM_SLUG="signal-sms-short-code-random"
SMS_SHORT_CODE_RANDOM_NAME="Signal Short Code (Random)"
SMS_SHORT_CODE_RANDOM_PRICE="110000"   # $1,100/mo
SMS_SHORT_CODE_RANDOM_DESC="Monthly random short code rental. Requires SMS Scale+ plan."

SMS_SHORT_CODE_VANITY_SLUG="signal-sms-short-code-vanity"
SMS_SHORT_CODE_VANITY_NAME="Signal Short Code (Vanity)"
SMS_SHORT_CODE_VANITY_PRICE="160000"   # $1,600/mo
SMS_SHORT_CODE_VANITY_DESC="Monthly vanity short code rental. Requires SMS Scale+ plan."

# ══════════════════════════════════════════════════════════════════════════════
# SMS — One-time setup fees
# ══════════════════════════════════════════════════════════════════════════════

SMS_SHORT_CODE_SETUP_SLUG="signal-sms-short-code-setup"
SMS_SHORT_CODE_SETUP_NAME="Signal Short Code Setup"
SMS_SHORT_CODE_SETUP_PRICE="65000"   # $650 one-time
SMS_SHORT_CODE_SETUP_DESC="One-time short code provisioning and registration fee."

SMS_SHORT_CODE_MMS_SETUP_SLUG="signal-sms-short-code-mms-setup"
SMS_SHORT_CODE_MMS_SETUP_NAME="Signal Short Code MMS Setup"
SMS_SHORT_CODE_MMS_SETUP_PRICE="50000"   # $500 one-time
SMS_SHORT_CODE_MMS_SETUP_DESC="One-time MMS enablement fee for a short code."

# ══════════════════════════════════════════════════════════════════════════════
# SMS — Segment top-up packs (one-time, rollover)
# setup_sms_segment_pack args: name slug segments price_cents description
# ══════════════════════════════════════════════════════════════════════════════

SMS_PACK_1K_NAME="SMS Segments 1K"
SMS_PACK_1K_SLUG="signal-sms-segments-1k"
SMS_PACK_1K_SEGMENTS="1000"
SMS_PACK_1K_PRICE="1300"    # $13 — $0.013/segment
SMS_PACK_1K_DESC="1,000 SMS segment top-up. \$0.013/segment."

SMS_PACK_5K_NAME="SMS Segments 5K"
SMS_PACK_5K_SLUG="signal-sms-segments-5k"
SMS_PACK_5K_SEGMENTS="5000"
SMS_PACK_5K_PRICE="6000"    # $60 — $0.012/segment
SMS_PACK_5K_DESC="5,000 SMS segment top-up. \$0.012/segment."

SMS_PACK_25K_NAME="SMS Segments 25K"
SMS_PACK_25K_SLUG="signal-sms-segments-25k"
SMS_PACK_25K_SEGMENTS="25000"
SMS_PACK_25K_PRICE="27500"   # $275 — $0.011/segment
SMS_PACK_25K_DESC="25,000 SMS segment top-up. \$0.011/segment."

SMS_PACK_100K_NAME="SMS Segments 100K"
SMS_PACK_100K_SLUG="signal-sms-segments-100k"
SMS_PACK_100K_SEGMENTS="100000"
SMS_PACK_100K_PRICE="100000"   # $1,000 — $0.010/segment
SMS_PACK_100K_DESC="100,000 SMS segment top-up. \$0.010/segment."